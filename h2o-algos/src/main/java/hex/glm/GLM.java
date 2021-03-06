package hex.glm;

import hex.DataInfo;
import hex.Model;
import hex.SupervisedModelBuilder;
import hex.glm.GLMModel.FinalizeAndUnlockTsk;
import hex.glm.GLMModel.GLMOutput;
import hex.glm.GLMModel.GLMParameters.Family;
import hex.glm.GLMModel.GLMParameters;
import hex.glm.GLMModel.GLMParameters.Link;
import hex.glm.GLMModel.GLMParameters.Solver;
import hex.glm.GLMTask.*;
import hex.glm.LSMSolver.ADMMSolver;
import hex.optimization.L_BFGS;
import hex.optimization.L_BFGS.*;
import hex.schemas.GLMV2;
import hex.schemas.ModelBuilderSchema;
import jsr166y.CountedCompleter;
import water.*;
import water.H2O.H2OCallback;
import water.H2O.H2OCountedCompleter;
import water.util.ArrayUtils;
import water.util.Log;
import water.util.MRUtils.ParallelTasks;
import water.util.MathUtils;
import water.util.ModelUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by tomasnykodym on 8/27/14.
 *
 * Generalized linear model implementation.
 * TODO: GLM will use a threshold during predict to do binomial classification, but
 * GLMModel currently never returns Binomial as its ModelCategory.
 */
public class GLM extends SupervisedModelBuilder<GLMModel,GLMModel.GLMParameters,GLMModel.GLMOutput> {
  private static final double LINE_SEARCH_STEP = .7;
  private static final int NUM_LINE_SEARCH_STEPS = 32;
  @Override
  public Model.ModelCategory[] can_build() {
    return new Model.ModelCategory[]{
            Model.ModelCategory.Regression,
            // Model.ModelCategory.Binomial, // see TODO comment above.
    };
  }

  public GLM(Key dest, String desc, GLMModel.GLMParameters parms) { super(dest, desc, parms); init(false); }
  public GLM(GLMModel.GLMParameters parms) { super("GLM", parms); init(false); }

  private static class TooManyPredictorsException extends RuntimeException {}

  @Override public void init(boolean expensive) {
    super.init(expensive);
    if(expensive) {
      if(_parms._link == Link.family_default)
        _parms._link = _parms._family.defaultLink;
    }
    _parms.validate(this);

  }

  @Override
  public ModelBuilderSchema schema() {
    return new GLMV2();
  }

  private static final int WORK_TOTAL = 100000000;
  private boolean _clean_enums;
  @Override
  public Job<GLMModel> trainModel() {
    _clean_enums = _parms._convert_to_enum && !_response.isEnum();
    _parms.read_lock_frames(this);
    init(true);                 // Expensive tests & conversions
    DataInfo dinfo = new DataInfo(Key.make(),_train,_valid, 1, _parms._use_all_factor_levels || _parms._lambda_search, _parms._standardize ? DataInfo.TransformType.STANDARDIZE : DataInfo.TransformType.NONE, DataInfo.TransformType.NONE, true);
    DKV.put(dinfo._key,dinfo);
    H2OCountedCompleter cmp = new H2OCountedCompleter(){
      AtomicBoolean _gotException = new AtomicBoolean(false);
      @Override public void compute2(){}
      @Override
      public void onCompletion(CountedCompleter cc){
        done();
        _parms.read_unlock_frames(GLM.this);
        if( _clean_enums ) {
          train().lastVec().remove();
          if (valid() != null) valid().lastVec().remove();
        }
      }
      @Override public boolean onExceptionalCompletion(Throwable ex, CountedCompleter cc){
        if(!_gotException.getAndSet(true)) {
          Job thisJob = DKV.getGet(_key);
          if (thisJob._state == JobState.CANCELLED) {
             failed(ex);
          }
          _parms.read_unlock_frames(GLM.this);
          if( _clean_enums ) {
            train().lastVec().remove();
            if (valid() != null) valid().lastVec().remove();
          }
          return true;
        }
        return false;
      }
    };
    start(cmp, WORK_TOTAL);
    H2O.submitTask(new GLMDriver(cmp, dinfo));
    return this;
  }

  private static double GLM_GRAD_EPS = 1e-4; // done (converged) if subgrad < this value.
  private static final int MAX_ITERATIONS_PER_LAMBDA = 10;
  private static final int MAX_ITER = 50;
  private static final int sparseCoefThreshold = 750;
  private static final double beta_epsilon = 1e-4;

  /**
   * Encapsulates state of the computation.
   */
  public static final class GLMTaskInfo extends Iced {
    final long      _nobs;     // number of observations in our dataset
    final double    _ymu;      // actual mean of the response
    final double    _lambdaMax;// lambda max of the current dataset
    double [] _beta;     // full solution at previous lambda (or null)
    GradientInfo    _ginfo;
    int             _iter;
    int             _max_iter;
//    private double _lastLambda;
    float        [] _thresholds;

    // these are not strictly state variables
    // I put them here to have all needed info in state object (so I only need to keep State[] info when doing xval)
    final Key             _dstKey;
    final DataInfo        _dinfo;
    final GLMParameters   _params;

    L_BFGS _lbfgs;      // lbfgs solver if we use lbfgs or null
    GradientInfo _gOld; // gradient info of the last returned result

    public GLMTaskInfo(Key dstKey, DataInfo dinfo, GLMParameters params, long nobs, double ymu, double lmax, double[] beta, GradientInfo ginfo){
      _dstKey = dstKey;
      _dinfo = dinfo;
      _params = params;
      _nobs = nobs;
      _ymu = ymu;
      _lambdaMax = lmax;
//      _lastLambda = lambda;
      _beta = beta;
      _ginfo = ginfo;
      _max_iter = _params._lambda_search ?MAX_ITERATIONS_PER_LAMBDA:MAX_ITER;
      if(_params._family == Family.binomial)
        _thresholds = ModelUtils.DEFAULT_THRESHOLDS;

    }
    public void adjustToNewLambda( double currentLambda, double newLambda) {
      assert newLambda < currentLambda:"newLambda = " + newLambda + ", last lambda = " + currentLambda;
      double l2diff = (newLambda - currentLambda) * (1 - _params._alpha[0]);
      for(int i = 0; i < _ginfo._gradient.length - (_dinfo._intercept?1:0); ++i)
        _ginfo._gradient[i] += l2diff * _beta[i];
      _ginfo = new GradientInfo(_ginfo ._objVal + .5 * l2diff * ArrayUtils.l2norm2(_beta,_dinfo._intercept), _ginfo._gradient);
    }
  }


  /**
   * Task to compute GLM solution for a particular (single) lambda value.
   * Can be warmstarted by passing in a state of previous computation so e.g. incremental strong rules can be
   * applied.
   *
   * The performs iterative reweighted least squares algorithm with elastic net penalty.
   *
   */
  public static final class GLMLambdaTask extends DTask<GLMLambdaTask>{
    DataInfo _activeData;
    GLMTaskInfo _taskInfo;
    final double _currentLambda;
    final double _lastLambda;
    int _iter;
    final Key _jobKey;
    Key _progressKey;
    long _start_time;
    double _addedL2;
    final boolean _forceLBFGS;
    public GLMLambdaTask(H2OCountedCompleter cmp, Key jobKey, Key progressKey, GLMTaskInfo state, double lastLambda, double currentLambda, boolean forceLBFGS){
      super(cmp);
      _taskInfo = state;
      assert DKV.get(_taskInfo._dinfo._key) != null;
      _currentLambda = currentLambda;
      _lastLambda = lastLambda;
      _jobKey = jobKey;
      _progressKey = progressKey;
      _forceLBFGS = forceLBFGS;
    }

    private String LogInfo(String msg){
      msg = "GLM2[dest=" + _taskInfo._dstKey + ", iteration=" + _iter + ", lambda = " + _currentLambda + "]: " + msg;
      Log.info(msg);
      return msg;
    }
    int [] _activeCols;

    /**
     * Apply strong rules to filter out expected innactive (with zero coefficient) predictors.
     * @return indeces of expected active predictors.
     */
    private int [] activeCols(final double l1, final double l2, final double [] grad) {
      int selected = 0;
      int [] cols = null;
      if (_taskInfo._params._alpha[0] > 0) {
        final double rhs = _taskInfo._params._alpha[0] * (2 * l1 - l2);
        cols = MemoryManager.malloc4(_taskInfo._dinfo.fullN());
        int j = 0;
        if (_activeCols == null) _activeCols = new int[]{-1};
        for (int i = 0; i < _taskInfo._dinfo.fullN(); ++i)
          if ((j < _activeCols.length && i == _activeCols[j]) || grad[i] > rhs || grad[i] < -rhs) {
            cols[selected++] = i;
            if (j < _activeCols.length && i == _activeCols[j]) ++j;
          }
      }
      if(_taskInfo._params._alpha[0] == 0 || selected == _taskInfo._dinfo.fullN()){
        _activeCols = null;
        _activeData = _taskInfo._dinfo;
        selected = _taskInfo._dinfo.fullN();
      } else {
        _activeCols = Arrays.copyOf(cols, selected);
        _activeData = _taskInfo._dinfo.filterExpandedColumns(_activeCols);
        assert DKV.get(_activeData._key) != null;
      }
      LogInfo("strong rule at lambda_value=" + l1 + ", got " + selected + " active cols out of " + _taskInfo._dinfo.fullN() + " total.");
      assert _activeCols == null || _activeData.fullN() == _activeCols.length:LogInfo("mismatched number of cols, got " + _activeCols.length + " active cols, but data info claims " + _activeData.fullN());
      return _activeCols;
    }

    /**
     * Encapsulates state needed for line search i.e. previous solution and it's gradient and objective value.
     */
    private static final class IterationInfo {
      final double [] _beta;
      final double [] _grad;
      final double    _objval;
      final int       _iter;
      public IterationInfo(int iter, double [] beta, double [] grad, double objval){
        _iter = iter;
        _beta = beta;
        _grad = grad;
        _objval = objval;
      }
    }
    private transient IterationInfo _lastResult;

    private double [] setSubmodel(final double[] newBeta, GLMValidation val, H2O.H2OCountedCompleter cmp){
      double [] fullBeta = (_activeCols == null || newBeta == null)?newBeta:expandVec(newBeta,_activeCols, _taskInfo._dinfo.fullN()+1);
      if(fullBeta == null){
        fullBeta = MemoryManager.malloc8d(_taskInfo._dinfo.fullN()+1);
        fullBeta[fullBeta.length-1] = _taskInfo._params.linkInv(_taskInfo._ymu);
      }
      final double [] newBetaDeNorm;
      if(_taskInfo._dinfo._predictor_transform == DataInfo.TransformType.STANDARDIZE) {
        newBetaDeNorm = fullBeta.clone();
        double norm = 0.0;        // Reverse any normalization on the intercept
        // denormalize only the numeric coefs (categoricals are not normalized)
        final int numoff = _taskInfo._dinfo.numStart();
        for( int i=numoff; i< fullBeta.length-1; i++ ) {
          double b = newBetaDeNorm[i]* _taskInfo._dinfo._normMul[i-numoff];
          norm += b* _taskInfo._dinfo._normSub[i-numoff]; // Also accumulate the intercept adjustment
          newBetaDeNorm[i] = b;
        }
        newBetaDeNorm[newBetaDeNorm.length-1] -= norm;
      } else
        newBetaDeNorm = null;
      GLMModel.setSubmodel(cmp, _taskInfo._dstKey, _currentLambda, newBetaDeNorm == null ? fullBeta : newBetaDeNorm, newBetaDeNorm == null ? null : fullBeta, (_iter + 1), System.currentTimeMillis() - _start_time, _taskInfo._dinfo.fullN() >= sparseCoefThreshold, val);
      return fullBeta;
    }



    /**
     * Computes the full gradient (gradient for all predictors) and checks line search condition (gradient has no NaNs/Infs) and the KKT conditions
     * for the underlying optimization problem. If some inactive columns violate the KKTs,
     * then they are added into the active set and solution is recomputed (rare), otherwise we just update the model in the K/V with this new solution
     * and finish.
     *
     * @param newBeta - computed solution
     * @param failedLineSearch - boolean flag if we're already comming from failed line-search (unable to proceed) - in that case line search is never performed.
     */
    protected void checkKKTAndComplete(final double [] newBeta, final boolean failedLineSearch){
      H2O.H2OCountedCompleter cmp = (H2O.H2OCountedCompleter)getCompleter();
      cmp.addToPendingCount(1);
      final double [] fullBeta;
      if(newBeta == null){
        fullBeta = MemoryManager.malloc8d(_taskInfo._dinfo.fullN()+1);
        fullBeta[fullBeta.length-1] = _taskInfo._params.linkInv(_taskInfo._ymu);
      } else
        fullBeta = expandVec(newBeta,_activeCols, _taskInfo._dinfo.fullN()+1);

      // now we need full gradient (on all columns) using this beta
      new GLMIterationTask(_jobKey, _taskInfo._dinfo, _taskInfo._params,false,true,true,fullBeta, _taskInfo._ymu,1.0/ _taskInfo._nobs, _taskInfo._thresholds, new H2O.H2OCallback<GLMIterationTask>(cmp) {
        @Override public String toString(){
          return "checkKKTAndComplete.Callback, completer = " + getCompleter() == null?"null":getCompleter().toString();
        }
        @Override
        public void callback(final GLMIterationTask glmt2) {
          // first check KKT conditions!
          final double [] grad = glmt2._ginfo._gradient;
          if(ArrayUtils.hasNaNsOrInfs(grad)){
            if(!failedLineSearch) {
              LogInfo("Check KKT got NaNs. Invoking line search");
              _taskInfo._params._higher_accuracy = true;
              getCompleter().addToPendingCount(1);
              new GLMTask.GLMLineSearchTask(_activeData,_taskInfo._params,1.0/_taskInfo._nobs,_lastResult._beta,ArrayUtils.subtract( contractVec(fullBeta, _activeCols),_lastResult._beta),LINE_SEARCH_STEP,NUM_LINE_SEARCH_STEPS, new LineSearchIteration(getCompleter())).asyncExec(_activeData._adaptedFrame);;
//              new GLMGradientTask(_activeData, _taskInfo._params, _currentLambda * (1-_taskInfo._params._alpha[0]),, NUM_LINE_SEARCH_STEPS, LINE_SEARCH_STEP), 1.0/_taskInfo._nobs, new LineSearchIteration(getCompleter(), ArrayUtils.subtract(newBeta, _lastResult._beta) )).asyncExec(_activeData._adaptedFrame);
              return;
            } else {
              // TODO: add warning and break the lambda search? Or throw Exception?
              LogInfo("got NaNs/Infs in gradient at lambda " + _currentLambda);
            }
          }
          // check the KKT conditions and filter data for the next lambda_value
          // check the gradient
          double[] subgrad = grad.clone();
          ADMMSolver.subgrad(_taskInfo._params._alpha[0], _currentLambda, fullBeta, subgrad);
          double err = GLM_GRAD_EPS;
          if (!failedLineSearch &&_activeCols != null) {
            for (int c : _activeCols)
              if (subgrad[c] > err) err = subgrad[c];
              else if (subgrad[c] < -err) err = -subgrad[c];
            int[] failedCols = new int[64];
            int fcnt = 0;
            for (int i = 0; i < grad.length - 1; ++i) {
              if (Arrays.binarySearch(_activeCols, i) >= 0) continue;
              if (subgrad[i] > err || -subgrad[i] > err) {
                if (fcnt == failedCols.length)
                  failedCols = Arrays.copyOf(failedCols, failedCols.length << 1);
                failedCols[fcnt++] = i;
              }
            }
            if (fcnt > 0) {
              final int n = _activeCols.length;
              int [] oldCols = _activeCols;
              _activeCols = Arrays.copyOf(_activeCols, _activeCols.length + fcnt);
              for (int i = 0; i < fcnt; ++i)
                _activeCols[n + i] = failedCols[i];
              if(_lastResult != null)
                _lastResult = new IterationInfo(_lastResult._iter, resizeVec(_lastResult._beta, _activeCols, oldCols, _taskInfo._dinfo.fullN() + 1),resizeVec(_lastResult._grad,_activeCols, oldCols, _taskInfo._dinfo.fullN() + 1), _lastResult._objval);
              Arrays.sort(_activeCols);
              LogInfo(fcnt + " variables failed KKT conditions check! Adding them to the model and continuing computation.(grad_eps = " + err + ", activeCols = " + (_activeCols.length > 100?"lost":Arrays.toString(_activeCols)));
              _activeData = _taskInfo._dinfo.filterExpandedColumns(_activeCols);
              // NOTE: tricky completer game here:
              // We expect 0 pending in this method since this is the end-point, ( actually it's racy, can be 1 with pending 1 decrement from the original Iteration callback, end result is 0 though)
              // while iteration expects pending count of 1, so we need to increase it here (Iteration itself adds 1 but 1 will be subtracted when we leave this method since we're in the callback which is called by onCompletion!
              // [unlike at the start of nextLambda call when we're not inside onCompletion]))
              getCompleter().addToPendingCount(1);
              new GLMIterationTask(_jobKey, _activeData, _taskInfo._params, true, true, true, contractVec(glmt2._beta, _activeCols), _taskInfo._ymu, 1.0/ _taskInfo._nobs, _taskInfo._thresholds, new Iteration(getCompleter())).asyncExec(_activeData._adaptedFrame);
              return;
            }
          }
          // update the state
          _taskInfo._beta = glmt2._beta;
          _taskInfo._ginfo = glmt2._ginfo;
          _taskInfo._iter = _iter;

          int diff = MAX_ITERATIONS_PER_LAMBDA - _iter + _taskInfo._iter;
          if(diff > 0)
            new Job.ProgressUpdate(diff).fork(_progressKey); // update progress
          setSubmodel(newBeta, glmt2._val,(H2O.H2OCountedCompleter)getCompleter().getCompleter());
        }
      }).asyncExec(_taskInfo._dinfo._adaptedFrame);
    }

    private static final boolean isSparse(water.fvec.Frame f) {
      int scount = 0;
      for(water.fvec.Vec v:f.vecs())
        if((v.nzCnt() << 3) > v.length())
          scount++;
      return (f.numCols() >> 1) < scount;
    }

    private GradientInfo adjustL2(GradientInfo ginfo, double [] coefs, double lambdaDiff) {
      for(int i = 0; i < coefs.length-1; ++i)
        ginfo._gradient[i] += lambdaDiff * coefs[i];
      return ginfo;
    }

    // GLMIterationTask(Key jobKey, DataInfo dinfo, GLMModel.GLMParameters glm, boolean computeGram, boolean validate, boolean computeGradient, double [] beta, double ymu, double reg, float [] thresholds, H2OCountedCompleter cmp) {
    private MRTask makeGLMTask(Key jobKey, DataInfo dinfo, GLMParameters params, boolean computeGram, boolean validate, boolean computeGradient, double [] beta) {
      return null;
    }
    @Override
    protected void compute2() {
      _start_time = System.currentTimeMillis();
      assert _currentLambda < _taskInfo._lambdaMax;
      _iter = _taskInfo._iter;
      LogInfo("starting computation of lambda = " + _currentLambda + ", previous lambda = " +_lastLambda);
      int [] activeCols = activeCols(_currentLambda, _lastLambda, _taskInfo._ginfo._gradient);
      int n = activeCols == null?_taskInfo._dinfo.fullN():activeCols.length;
      if(n > _taskInfo._params._max_active_predictors)
        throw new TooManyPredictorsException();
      double [] beta = contractVec(_taskInfo._beta, _activeCols);
      boolean LBFGS = _forceLBFGS;
      if(beta == null) {
        beta = MemoryManager.malloc8d(_activeData.fullN() + (_activeData._intercept?1:0));
        if(_activeData._intercept)
          beta[beta.length-1] = _taskInfo._params.link(_taskInfo._ymu);
      }
      _lastResult = new IterationInfo(_taskInfo._iter,beta,contractVec(_taskInfo._ginfo._gradient,_activeCols), _taskInfo._ginfo._objVal);
      if(LBFGS) { // TODO add L1 pen handling!
        if(_taskInfo._params._alpha[0] > 0 || _activeCols != null)
          throw H2O.unimpl();
        Log.info("current lambda = " + _currentLambda);
        GradientSolver solver = (_activeData._adaptedFrame.numCols() >= 100 || isSparse(_activeData._adaptedFrame))
          ?new GLMGradientSolver(_taskInfo._params,_activeData, _currentLambda,_taskInfo._ymu,_taskInfo._nobs)
          :new GLMGradientSolver(_taskInfo._params, _activeData, _currentLambda,_taskInfo._ymu, _taskInfo._nobs);

        long t1 = System.currentTimeMillis();
        if(_taskInfo._lbfgs == null)
          _taskInfo._lbfgs = new L_BFGS();
        GradientInfo gOld = _taskInfo._gOld == null
          ?solver.getGradient(beta)
          :_taskInfo._ginfo;
        final int workInc = WORK_TOTAL/_taskInfo._params._lambda.length/_taskInfo._lbfgs.maxIter();
        L_BFGS.Result r = _taskInfo._lbfgs.solve(solver, beta, gOld, new ProgressMonitor() {
          @Override
          public boolean progress(GradientInfo ginfo) {
            update(workInc, _jobKey);
            // todo update the model here wo we can show intermediate results
            return Job.isRunning(_jobKey);
          }
        });
        long t2 = System.currentTimeMillis();
        Log.info("L_BFGS (k = " + _taskInfo._lbfgs.k() + ") done after " + r.iter + " iterations and " + ((t2-t1)/1000) + " seconds, objval = " + r.ginfo._objVal + ", penalty = " + (_currentLambda * .5 * ArrayUtils.l2norm2(beta,true)) + ",  gradient norm2 = " + (MathUtils.l2norm2(r.ginfo._gradient)));

        _taskInfo._gOld = r.ginfo;
        double [] newBeta = r.coefs;
        // update the state
        _taskInfo._beta = newBeta;
        _taskInfo._iter = (_iter += r.iter);
        setSubmodel(newBeta,null,this);
        tryComplete();
      } else // fork off ADMM iteration
        new GLMIterationTask(_jobKey, _activeData, _taskInfo._params, true, false, false, beta, _taskInfo._ymu, 1.0 / _taskInfo._nobs, _taskInfo._thresholds, new Iteration(this)).asyncExec(_activeData._adaptedFrame);
    }
    private class Iteration extends H2O.H2OCallback<GLMIterationTask> {
      public final long _iterationStartTime;
      final boolean _countIteration;
      final double _lineSearchStep;
      public Iteration(CountedCompleter cmp){ this(cmp,true,1.0);}
      public Iteration(CountedCompleter cmp, boolean countIteration,double lineSearchStep){
        super((H2O.H2OCountedCompleter)cmp);
        _lineSearchStep = lineSearchStep;
        _countIteration = countIteration;
        _iterationStartTime = System.currentTimeMillis(); }

      @Override public void callback(final GLMIterationTask glmt){
        if(_jobKey != null && !isRunning(_jobKey) )  throw new JobCancelledException();
        assert _activeCols == null || glmt._beta == null || glmt._beta.length == (_activeCols.length+1):LogInfo("betalen = " + glmt._beta.length + ", activecols = " + _activeCols.length);
        assert _activeCols == null || _activeCols.length == _activeData.fullN();
        assert getCompleter().getPendingCount() <= 1 : LogInfo("unexpected pending count, expected <=  1, got " + getCompleter().getPendingCount()); // will be decreased by 1 after we leave this callback
        if (_countIteration) ++_iter;
        long callbackStart = System.currentTimeMillis();
        if(glmt.hasNaNsOrInf() || (glmt._computeGradient && L_BFGS.needLineSearch(_lineSearchStep, _lastResult._objval, glmt._ginfo._objVal, _lastResult._beta, glmt._beta, _lastResult._grad))){
          getCompleter().addToPendingCount(1);
          LogInfo("invoking line search");
          new GLMTask.GLMLineSearchTask(_activeData, _taskInfo._params, 1.0/_taskInfo._nobs, _lastResult._beta, ArrayUtils.subtract(glmt._beta, _lastResult._beta), LINE_SEARCH_STEP, NUM_LINE_SEARCH_STEPS, new LineSearchIteration(getCompleter())).asyncExec(_activeData._adaptedFrame);
          return;
        }
        if(glmt._newThresholds != null) {
          _taskInfo._thresholds = ArrayUtils.join(glmt._newThresholds[0], glmt._newThresholds[1]);
          Arrays.sort(_taskInfo._thresholds);
        }
        double gerr = Double.NaN;
        if (glmt._val != null && glmt._computeGradient) { // check gradient
          _lastResult = new IterationInfo(_iter, glmt._beta, glmt._ginfo._gradient, glmt._ginfo._objVal);
          double [] grad = _lastResult._grad.clone();
          ADMMSolver.subgrad(_taskInfo._params._alpha[0], _currentLambda, glmt._beta, grad);
          gerr = 0;
          for (double d : grad)
            if (d > gerr) gerr = d;
            else if (d < -gerr) gerr = -d;
          if(gerr <= GLM_GRAD_EPS){
            LogInfo("converged by reaching small enough gradient, with max |subgradient| = " + gerr  /* + ", coefs = " + Arrays.toString(glmt._beta) */);
            checkKKTAndComplete(glmt._beta,false);
            return;
          }
        }
        final double [] newBeta = MemoryManager.malloc8d(glmt._xy.length);
        long t1 = System.currentTimeMillis();
        ADMMSolver slvr = new ADMMSolver(_currentLambda, _taskInfo._params._alpha[0], GLM_GRAD_EPS, _addedL2);
        slvr.solve(glmt._gram,glmt._xy,glmt._yy,newBeta, _currentLambda * _taskInfo._params._alpha[0]);
        if(_lineSearchStep < 1){
          if(glmt._beta != null)
            for(int i = 0; i < newBeta.length; ++i)
              newBeta[i] = glmt._beta[i]*(1-_lineSearchStep) + _lineSearchStep*newBeta[i];
          else
            for(int i = 0; i < newBeta.length; ++i)
              newBeta[i] *= _lineSearchStep;
        }
        // print all info about iteration
        LogInfo("Gram computed in " + (callbackStart - _iterationStartTime) + "ms, " + (Double.isNaN(gerr)?"":"gradient = " + gerr + ",") + ", step = " + _lineSearchStep + ", ADMM: " + slvr.iterations + " iterations, " + (System.currentTimeMillis() - t1) + "ms (" + slvr.decompTime + "), subgrad_err=" + slvr.gerr);
        if (slvr._addedL2 > _addedL2) LogInfo("added " + (slvr._addedL2 - _addedL2) + "L2 penalty");
        new Job.ProgressUpdate(1).fork(_progressKey); // update progress
        _addedL2 = slvr._addedL2;
        if (ArrayUtils.hasNaNsOrInfs(newBeta)) {
          throw new RuntimeException(LogInfo("got NaNs and/or Infs in beta"));
        } else {
          final double bdiff = beta_diff(glmt._beta, newBeta);
          if (_taskInfo._params._family == Family.gaussian || bdiff < beta_epsilon || _iter >= _taskInfo._max_iter) { // Gaussian is non-iterative and gradient is ADMMSolver's gradient => just validate and move on to the next lambda_value
            int diff = (int) Math.log10(bdiff);
            int nzs = 0;
            for (int i = 0; i < newBeta.length; ++i)
              if (newBeta[i] != 0) ++nzs;
            LogInfo("converged (reached a fixed point with ~ 1e" + diff + " precision), got " + nzs + " nzs");
            checkKKTAndComplete(newBeta, false);
            return;
          } else { // not done yet, launch next iteration
            if (glmt._beta != null)
              setSubmodel(glmt._beta, glmt._val, (H2O.H2OCountedCompleter) getCompleter().getCompleter()); // update current intermediate result
            final boolean validate = _taskInfo._params._higher_accuracy || (_iter % 5) == 0;
            getCompleter().addToPendingCount(1);
            new GLMIterationTask(_jobKey,_activeData,glmt._glm, true, validate, validate, newBeta, _taskInfo._ymu,1.0/ _taskInfo._nobs, _taskInfo._thresholds, new Iteration(getCompleter(),true,Math.min(1,2*_lineSearchStep))).asyncExec(_activeData._adaptedFrame);
          }
        }
      }
    }
    private class LineSearchIteration extends H2O.H2OCallback<GLMLineSearchTask> {
      LineSearchIteration(CountedCompleter cmp){
        super((H2O.H2OCountedCompleter)cmp);
      }
      @Override public void callback(final GLMLineSearchTask lst) {
        assert getCompleter().getPendingCount() <= 1:"unexpected pending count, expected 1, got " + getCompleter().getPendingCount();
        LineSearchSol ls = L_BFGS.doLineSearch(lst._objVals, _taskInfo._ginfo, lst._direction, 1, LINE_SEARCH_STEP);
        if(ls != null) {
          LogInfo("line search: found admissible step = " + ls.step + ",  objval = " + ls.objVal);
          _taskInfo._params._higher_accuracy = true;
          getCompleter().addToPendingCount(1);
          double [] beta = ArrayUtils.add(lst._beta, ArrayUtils.mult(lst._direction, ls.step));
          new GLMIterationTask(_jobKey, _activeData, _taskInfo._params, true, true, true, beta, _taskInfo._ymu, 1.0/ _taskInfo._nobs, _taskInfo._thresholds, new Iteration(getCompleter(),false, ls.step)).asyncExec(_activeData._adaptedFrame);
          return;
        }
      // no line step worked converge
        if(!_taskInfo._params._higher_accuracy){ // start from scratch
          _taskInfo._params._higher_accuracy = true;
          int add2iter = (_iter - _taskInfo._iter);
          LogInfo("Line search failed to progress, rerunning current lambda from scratch with high accuracy on, adding " + add2iter + " to max iterations");
          _taskInfo._max_iter += add2iter;
          getCompleter().addToPendingCount(1);
          new GLMIterationTask(_jobKey,_activeData, _taskInfo._params,true,true,true,contractVec(_taskInfo._beta,_activeCols), _taskInfo._ymu,1.0/ _taskInfo._nobs, _taskInfo._thresholds, new Iteration(getCompleter(),false,1)).asyncExec(_activeData._adaptedFrame);
          return;
        }
        LogInfo("Line search did not find feasible step, converged.");
        checkKKTAndComplete(_lastResult._beta,true);
      }
    }


  }

  private final double lmax(GLMGradientTask gLmax) {
    return Math.max(ArrayUtils.maxValue(gLmax._gradient),-ArrayUtils.minValue(gLmax._gradient))/Math.max(1e-3,_parms._alpha[0]);
  }
  /**
   * Contains implementation of the glm algo.
   * It's a DTask so it can be computed on other nodes (to distributed single node part of the computation).
   */
  public final class GLMDriver extends DTask<GLMDriver> {
    final DataInfo _dinfo;
    transient ArrayList<DataInfo> _foldInfos = new ArrayList<DataInfo>();

    double [] lambdas;
    final GLMTaskInfo[] _state;
    int             _lambdaId;
    int   _maxLambda;
    transient AtomicBoolean _gotException = new AtomicBoolean();

    public GLMDriver(H2OCountedCompleter cmp, DataInfo dinfo){
      super(cmp);
      _dinfo = dinfo;
      _state = _parms._n_folds > 1?new GLMTaskInfo[_parms._n_folds +1]:new GLMTaskInfo[1];
    }

    private double [] nullBeta(DataInfo dinfo, GLMParameters params, double ymu){
      double [] beta = MemoryManager.malloc8d(dinfo.fullN()+1);
      beta[beta.length-1] = params.linkInv(ymu);
      return beta;
    }

    private void doCleanup(){
      DKV.remove(_dinfo._key);
      for(DataInfo dinfo:_foldInfos)
        DKV.remove(dinfo._key);
    }
    @Override public boolean onExceptionalCompletion(final Throwable ex, CountedCompleter cc){
      doCleanup();
      if(!_gotException.getAndSet(true)){
        if(ex instanceof TooManyPredictorsException){
          // TODO add a warning
          _maxLambda = _lambdaId;
          this.tryComplete();
          return false;
        }
        new RemoveCall(null, _dest).invokeTask();
        return true;
      }
      return false;
    }
    @Override public void onCompletion(CountedCompleter cc){
      doCleanup();
      H2OCountedCompleter cmp = (H2OCountedCompleter)getCompleter();
      cmp.addToPendingCount(1);
      new FinalizeAndUnlockTsk(cmp, _dest, _key).fork();
    }
    @Override
    protected void compute2() {
      if(_parms._alpha.length > 1){ // just fork off grid search
        return;
      }
      // compute lambda max
      // if this is cross-validated task, don't do actual computation,
      // just fork off the nfolds+1 tasks and wait for the results

      if(_parms._nlambdas == -1)_parms._nlambdas = 100;
      if(_parms._lambda_search && _parms._nlambdas <= 1)
        throw new IllegalArgumentException("GLM2(" + _dest + ") nlambdas must be > 1 when running with lambda search.");
      Futures fs = new Futures();
      new YMUTask(_dinfo, new H2O.H2OCallback<YMUTask>(this) {
        @Override
        public String toString(){
          return "YMUTask callback. completer = " + getCompleter() != null?"null":getCompleter().toString();
        }
        @Override
        public void callback(final YMUTask ymut) {
          if (ymut._yMin == ymut._yMax)
            throw new IllegalArgumentException("GLM2(" + _dest + "): attempted to run with constant response. Response == " + ymut._yMin + " for all rows in the training set.");
          final double gYmu;
          final long nobs;
          gYmu = ymut._ymu;
          nobs = ymut._nobs;

          H2O.H2OCountedCompleter cmp = (H2O.H2OCountedCompleter)getCompleter();
          cmp.addToPendingCount(1);
          double [] beta = MemoryManager.malloc8d(_dinfo.fullN() + (_dinfo._intercept?1:0));
          if(_dinfo._intercept)
            beta[beta.length-1] = _parms.link(gYmu);
          new GLMTask.GLMGradientTask( _dinfo, _parms, gYmu, beta, 1.0/nobs, new H2OCallback<GLMGradientTask>(cmp){
            @Override
            public String toString(){
              return "LMAXTask callback. completer = " + (getCompleter() != null?"NULL":getCompleter().toString());
            }
            @Override public void callback(final GLMGradientTask gLmaxTsk){
              // public GLMModel(Key selfKey, String[] names, String[][] domains, GLMParameters parms, GLMOutput output) {
              GLMOutput glmOutput = new GLMOutput(GLM.this,_dinfo,_parms._family == Family.binomial);
              String warning = null;
              final double gLmax = lmax(gLmaxTsk);
              if(_parms._lambda_search) {
                assert !Double.isNaN(gLmax) : "running lambda_value search, but don't know what is the lambda_value max!";
                if (_parms._lambda_min_ratio == -1)
                  _parms._lambda_min_ratio = nobs > 25 * _dinfo.fullN() ? 1e-4 : 1e-2;
                  final double d = Math.pow(_parms._lambda_min_ratio, 1.0 / (_parms._nlambdas - 1));
                  lambdas = new double[_parms._nlambdas];
                  lambdas[0] =gLmax;
                  if (_parms._nlambdas == 1)
                    throw new IllegalArgumentException("Number of lambdas must be > 1 when running with lambda_search!");
                  for (int i = 1; i < lambdas.length; ++i)
                    lambdas[i] = lambdas[i - 1] * d;
              } else {
                if(_parms._lambda == null || _parms._lambda.length == 0)
                  lambdas = new double[]{1e-2*gLmax};
                else
                  lambdas = _parms._lambda;
                int i = 0;
                while(i < lambdas.length && lambdas[i] >= gLmax)++i;
                if(i == lambdas.length)
                  throw new IllegalArgumentException("Given lambda(s) are all > lambda_max = " + gLmax + ", have nothing to run with. lambda = " + Arrays.toString(lambdas));
                if(i > 0) {
                  warning = "Removed " + i + " lambdas greater than lambda_max.";
                }
                lambdas = ArrayUtils.append(new double[]{gLmax},Arrays.copyOfRange(lambdas,i,lambdas.length));
              }
              if(lambdas.length > 1) {
                GLMValidation val = new GLMValidation(_dinfo._adaptedFrame._key,ymut._ymu,_parms,0);
                val.auc = .5;
                double nullDev = gLmaxTsk._objVal * ymut._nobs;
                val.null_deviance = nullDev;
                val.residual_deviance = nullDev;
                glmOutput.addNullSubmodel(gLmax, _parms.link(gYmu), val); // todo add null validation
              }
              _maxLambda = lambdas.length;
              GLMModel model = new GLMModel(_dest, _parms, glmOutput, _dinfo, gYmu,gLmax,nobs);
              if(warning != null)
                model.addWarning(warning);
              model.delete_and_lock(_key);
              _state[0] = new GLMTaskInfo(_dest,_dinfo,_parms,ymut._nobs,ymut._ymu,gLmax,null,new GradientInfo(gLmaxTsk._objVal,gLmaxTsk._gradient));
              getCompleter().addToPendingCount(1);
              if(_parms._n_folds > 1){
                final H2OCountedCompleter cmp = new H2OCallback((H2OCountedCompleter)getCompleter()) {
                  @Override
                  public void callback(H2OCountedCompleter h2OCountedCompleter) {
                    GLMLambdaTask [] tasks = new GLMLambdaTask[_state.length];
                    for(int i = 0; i < tasks.length; ++i)
                      tasks[i] = new GLMLambdaTask(null, _key,_progressKey,_state[i],gLmax, lambdas[_lambdaId], _parms._solver == Solver.L_BFGS);
                    // now we have computed lmax for all n_folds model and solution for global lmax (lmax on the whole dataset) for all n_folds
                    // just start tasks to compute the first lambda in parallel for all n_folds.
                    getCompleter().addToPendingCount(1);
                    new ParallelTasks(new LambdaSearchIteration((H2OCountedCompleter)getCompleter(), _parms._solver == Solver.L_BFGS),tasks).fork();
                  }
                };
                cmp.addToPendingCount(_state.length-2);
                for(int i = 1; i < _state.length; ++i){
                  final int fi = i;
                  final GLMParameters params = (GLMParameters)_parms.clone();
                  params._n_folds = 0;
                  final DataInfo dinfo = _dinfo.getFold(i-1,_parms._n_folds);
                  _foldInfos.add(dinfo);
                  DKV.put(dinfo._key, dinfo);
                  Log.info("inserted dinfo for fold " + i + " under key " + dinfo._key);
                  if(i != 0){
                    // public LMAXTask(Key jobKey, DataInfo dinfo, GLMModel.GLMParameters glm, double ymu, long nobs, double alpha, float [] thresholds, H2OCountedCompleter cmp) {
                    // LMAXTask(DataInfo dinfo, GLMModel.GLMParameters params, double ymu, long nobs, H2OCountedCompleter cmp) {
                    double [] beta = MemoryManager.malloc8d(_dinfo.fullN() + (_dinfo._intercept?1:0));
                    if(_dinfo._intercept)
                      beta[beta.length-1] = _parms.link(ymut.ymu(fi-1));
                    new GLMGradientTask(dinfo, params, ymut.ymu(fi-1), beta, ymut.nobs(fi-1), new H2OCallback<GLMGradientTask>(cmp) {
                      @Override
                      public String toString(){
                        return "Xval LMAXTask callback., completer = " + getCompleter() == null?"null":getCompleter().toString();
                      }
                      @Override
                      public void callback(GLMGradientTask lLmaxTsk) {
                        // long nobs, double ymu, double lmax, double [] beta, double [] gradient
                        final double lLmax = lmax(lLmaxTsk);
                        Key dstKey = Key.make(_dest.toString() + "_xval_" + fi, (byte)1, Key.HIDDEN_USER_KEY, true, H2O.SELF);
                        _state[fi] = new GLMTaskInfo(dstKey,dinfo,params,ymut.nobs(fi-1),ymut.ymu(fi-1),lLmax, nullBeta(dinfo,params,ymut.ymu(fi-1)), new GradientInfo(lLmaxTsk._objVal,lLmaxTsk._gradient));
                        assert DKV.get(dinfo._key) != null;
                        new GLMModel(dstKey, params, new GLMOutput(GLM.this,dinfo,_parms._family == Family.binomial), dinfo, ymut.ymu(fi-1), gLmax, nobs).delete_and_lock(_key);
                        if(lLmax > gLmax){
                          getCompleter().addToPendingCount(1);
                          // lambda max for this n_fold is > than global lambda max -> it has non-trivial solution for global lambda max, need to compute it first.
                          new GLMLambdaTask((H2OCountedCompleter)getCompleter(), _key,_progressKey,_state[fi],gLmax,gLmax, _parms._solver == Solver.L_BFGS).fork();
                        }
                      }
                    }).asyncExec(dinfo._adaptedFrame);
                  }
                }
              } else {
                new GLMLambdaTask(new LambdaSearchIteration((H2OCountedCompleter) getCompleter(), _parms._solver == Solver.L_BFGS), _key, _progressKey, _state[0], gLmax, lambdas[++_lambdaId], _parms._solver == Solver.L_BFGS).fork();
              }
            }
          }).asyncExec(_dinfo._adaptedFrame);
        }
      }).asyncExec(_dinfo._adaptedFrame);
    }

    private class LambdaSearchIteration extends H2O.H2OCallback {
      final boolean _forceLBFGS;
      public LambdaSearchIteration(H2OCountedCompleter cmp, boolean forceLBFGS){super(cmp); _forceLBFGS = forceLBFGS; }

      @Override
      public void callback(H2OCountedCompleter h2OCountedCompleter) {
        double currentLambda = lambdas[_lambdaId];
        if(_parms._n_folds > 1){
          // copy the state over
          ParallelTasks<GLMLambdaTask> t = (ParallelTasks<GLMLambdaTask>)h2OCountedCompleter;
          for(int i = 0; i < t._tasks.length; ++i)
            _state[i] = t._tasks[i]._taskInfo;
          // launch xval-task to compute validations of xval models
          // getCompleter().addToPendingCount(1);
          // TODO ...
        }
        // now launch the next lambda

        if(++_lambdaId  < _maxLambda){
          getCompleter().addToPendingCount(1);
          double nextLambda = lambdas[_lambdaId];
          if(_parms._n_folds > 1){
            GLMLambdaTask [] tasks = new GLMLambdaTask[_state.length];
            H2OCountedCompleter cmp = new LambdaSearchIteration((H2OCountedCompleter)getCompleter(), _forceLBFGS);
            cmp.addToPendingCount(tasks.length-1);
            for(int i = 0; i < tasks.length; ++i) {
              tasks[i] = new GLMLambdaTask(cmp, _key, _progressKey, _state[i], currentLambda, nextLambda, _forceLBFGS);
            }
            new ParallelTasks(new LambdaSearchIteration((H2OCountedCompleter)getCompleter(), _forceLBFGS),tasks).fork();
          } else {
            _state[0].adjustToNewLambda(currentLambda,nextLambda);
            new GLMLambdaTask(new LambdaSearchIteration((H2OCountedCompleter) getCompleter(), _forceLBFGS), _key, _progressKey, _state[0], currentLambda, nextLambda, _forceLBFGS).fork();
          }
        }
      }
    }
  }
  private static final double beta_diff(double[] b1, double[] b2) {
    if(b1 == null)return Double.MAX_VALUE;
    double res = b1[0] >= b2[0]?b1[0] - b2[0]:b2[0] - b1[0];
    for( int i = 1; i < b1.length; ++i ) {
      double diff = b1[i] - b2[i];
      if(diff > res)
        res = diff;
      else if( -diff > res)
        res = -diff;
    }
    return res;
  }
  private static final double [] expandVec(double [] beta, final int [] activeCols, int fullN){
    assert beta != null;
    if (activeCols == null) return beta;
    double[] res = MemoryManager.malloc8d(fullN);
    int i = 0;
    for (int c : activeCols)
      res[c] = beta[i++];
    res[res.length - 1] = beta[beta.length - 1];
    return res;
  }
  private static final double [] contractVec(double [] beta, final int [] activeCols){
    if(beta == null)return null;
    if(activeCols == null)return beta.clone();
    double [] res = MemoryManager.malloc8d(activeCols.length+1);
    int i = 0;
    for(int c:activeCols)
      res[i++] = beta[c];
    res[res.length-1] = beta[beta.length-1];
    return res;
  }
  private static final double [] resizeVec(double[] beta, final int[] activeCols, final int[] oldActiveCols, int fullN){
    if(beta == null || Arrays.equals(activeCols,oldActiveCols))return beta;
    double [] full = expandVec(beta, oldActiveCols,fullN);
    if(activeCols == null)return full;
    return contractVec(full,activeCols);
  }


  protected static double l1norm(double[] beta){
    if(beta == null)return 0;
    double l1 = 0;
    for (int i = 0; i < beta.length-1; ++i)
      l1 += beta[i] < 0?-beta[i]:beta[i];
    return l1;
  }

  private static double penalty(double [] beta, double alpha, double lambda){
    return lambda*(alpha*l1norm(beta) + .5*(1-alpha)*ArrayUtils.l2norm(beta, true));
  }
  private static double  objval(GLMIterationTask glmt, double alpha, double lambda){
    return glmt._val.residual_deviance / glmt._nobs + penalty(glmt._beta,alpha,lambda);
  }




  public final static class GLMGradientSolver extends GradientSolver {
    final GLMParameters _glmp;
    final DataInfo _dinfo;
    final double _ymu;
    final double _lambda;
    final long _nobs;
    // line search params, do 48 steps by .75 decrement, min step = .8^31 = 0.0001339366
    int _nsteps = 32;

    public GLMGradientSolver(GLMParameters glmp, DataInfo dinfo, double lambda, double ymu, long nobs){
      _glmp = glmp;
      _dinfo = dinfo;
      _ymu = ymu;
      _nobs = nobs;
      _lambda = lambda;
      _step = .75;
    }

    @Override
    public GradientInfo getGradient(double[] beta) {
      GLMGradientTask gt = new GLMGradientTask(_dinfo,_glmp, _lambda, beta,1.0/_nobs).doAll(_dinfo._adaptedFrame);
      return new GradientInfo(gt._objVal, gt._gradient);
    }

    @Override
    public double[] lineSearch(double[] beta, double[] direction) {
      return new GLMLineSearchTask(_dinfo, _glmp, 1.0/_nobs, beta, direction, _step, _nsteps ).doAll(_dinfo._adaptedFrame)._objVals;
    }
  }

}
