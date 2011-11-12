package org.apache.mahout.math.hadoop.solver;

import java.io.IOException;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.common.AbstractJob;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.apache.mahout.math.hadoop.DistributedRowMatrix;
import org.apache.mahout.math.solver.ConjugateGradientSolver;
import org.apache.mahout.math.solver.Preconditioner;

/**
 * 
 * Distributed implementation of the conjugate gradient solver. More or less, this is just the standard solver
 * but wrapped with some methods that make it easy to run it on a DistributedRowMatrix.
 *  
 */

public class DistributedConjugateGradientSolver extends ConjugateGradientSolver implements Tool
{
  private Configuration conf; 
  private Map<String, String> parsedArgs;

  /**
   * 
   * Runs the distributed conjugate gradient solver programmatically to solve the system (A + lambda*I)x = b.
   * 
   * @param inputPath      Path to the matrix A
   * @param tempPath       Path to scratch output path, deleted after the solver completes
   * @param numRows        Number of rows in A
   * @param numCols        Number of columns in A
   * @param b              Vector b
   * @param preconditioner Optional preconditioner for the system
   * @param maxIterations  Maximum number of iterations to run, defaults to numCols
   * @param maxError       Maximum error tolerated in the result. If the norm of the residual falls below this, then the 
   *                       algorithm stops and returns. 

   * @return               The vector that solves the system.
   */
  public Vector runJob(Path inputPath, 
                       Path tempPath, 
                       int numRows, 
                       int numCols, 
                       Vector b, 
                       Preconditioner preconditioner, 
                       int maxIterations, 
                       double maxError) {
    DistributedRowMatrix matrix = new DistributedRowMatrix(inputPath, tempPath, numRows, numCols);
    matrix.setConf(conf);
        
    return solve(matrix, b, preconditioner, maxIterations, maxError);
  }
  
  @Override
  public Configuration getConf()
  {
    return conf;
  }

  @Override
  public void setConf(Configuration conf)
  {
    this.conf = conf;    
  }

  @Override
  public int run(String[] strings) throws Exception
  {
    Path inputPath = new Path(parsedArgs.get("--input"));
    Path outputPath = new Path(parsedArgs.get("--output"));
    Path tempPath = new Path(parsedArgs.get("--tempDir"));
    Path vectorPath = new Path(parsedArgs.get("--vector"));
    int numRows = Integer.parseInt(parsedArgs.get("--numRows"));
    int numCols = Integer.parseInt(parsedArgs.get("--numCols"));
    int maxIterations = parsedArgs.containsKey("--maxIter") ? Integer.parseInt(parsedArgs.get("--maxIter")) : numCols;
    double maxError = parsedArgs.containsKey("--maxError") 
        ? Double.parseDouble(parsedArgs.get("--maxError")) 
        : ConjugateGradientSolver.DEFAULT_MAX_ERROR;

    Vector b = loadInputVector(vectorPath);
    Vector x = runJob(inputPath, tempPath, numRows, numCols, b, null, maxIterations, maxError);
    saveOutputVector(outputPath, x);
    tempPath.getFileSystem(conf).delete(tempPath, true);
    
    return 0;
  }
  
  public DistributedConjugateGradientSolverJob job() {
    return new DistributedConjugateGradientSolverJob();
  }
  
  private Vector loadInputVector(Path path) throws IOException {
    FileSystem fs = path.getFileSystem(conf);
    SequenceFile.Reader reader = new SequenceFile.Reader(fs, path, conf);
    IntWritable key = new IntWritable();
    VectorWritable value = new VectorWritable();
    
    try {
      if (!reader.next(key, value)) {
        throw new IOException("Input vector file is empty.");      
      }
      return value.get();
    } finally {
      reader.close();
    }
  }
  
  private void saveOutputVector(Path path, Vector v) throws IOException {
    FileSystem fs = path.getFileSystem(conf);
    SequenceFile.Writer writer = new SequenceFile.Writer(fs, conf, path, IntWritable.class, VectorWritable.class);
    
    try {
      writer.append(new IntWritable(0), new VectorWritable(v));
    } finally {
      writer.close();
    }
  }
  
  public class DistributedConjugateGradientSolverJob extends AbstractJob {
    @Override
    public void setConf(Configuration conf) {
      DistributedConjugateGradientSolver.this.setConf(conf);
    }
    
    @Override
    public Configuration getConf() {
      return DistributedConjugateGradientSolver.this.getConf();
    }
    
    @Override
    public int run(String[] args) throws Exception
    {
      addInputOption();
      addOutputOption();
      addOption("numRows", "nr", "Number of rows in the input matrix", true);
      addOption("numCols", "nc", "Number of columns in the input matrix", true);
      addOption("vector", "b", "Vector to solve against", true);
      addOption("lambda", "l", "Scalar in A + lambda * I [default = 0]", "0.0");
      addOption("symmetric", "sym", "Is the input matrix square and symmetric?", "true");
      addOption("maxIter", "x", "Maximum number of iterations to run");
      addOption("maxError", "err", "Maximum residual error to allow before stopping");

      DistributedConjugateGradientSolver.this.parsedArgs = parseArguments(args);
      if (DistributedConjugateGradientSolver.this.parsedArgs == null) {
        return -1;
      } else {
        DistributedConjugateGradientSolver.this.setConf(new Configuration());
        return DistributedConjugateGradientSolver.this.run(args);
      }
    }    
  }

  public static void main(String[] args) throws Exception {
    ToolRunner.run(new DistributedConjugateGradientSolver().job(), args);
  }
}