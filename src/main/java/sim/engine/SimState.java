/*
  Copyright 2006 by Sean Luke and George Mason University
  Licensed under the Academic Free License version 3.0
  See the file "LICENSE" for more information
*/
package sim.engine;
import ec.util.*;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import java.text.*;
import java.lang.reflect.*;

/** SimState represents the simulation proper.  Your simulations generally will contain one top-level object which subclasses from SimState.

    <p>A SimState contains the random number generator and the simulator's schedule.  You should not change the schedule to another Schedule object.

    <p>When a simulation is begun, SimState's start() method is called.  Then the schedule is stepped some N times.  Last, the SimState's finish() method is called, and the simulation is over.

    <p>SimStates are serializable; if you wish to be able to checkpoint your simulation and read from checkpoints, you should endeavor to make all objects in the simulation serializable as well.  Prior to serializing to a checkpoint, preCheckpoint() is called.  Then after serialization, postCheckpoint() is called.  When a SimState is loaded from a checkpoint, awakeFromCheckpoint() is called to give you a chance to make any adjustments.  SimState also implements several methods which call these methods and then serialize the SimState to files and to streams.

    <p>SimState also maintains a private registry of AsynchronousSteppable objects, and handles pausing and resuming
    them during the checkpointing process, and killing them during finish() in case they had not completed yet.

    <p>If you override any of the methods foo() in SimState, should remember to <b>always</b> call super.foo() for any such method foo().
*/

public class SimState implements java.io.Serializable
    {
    private static final long serialVersionUID = 1;

    /** SimState 的随机数生成器 */
    public MersenneTwisterFast random;
    
    /** SimState 的调度器 */
    public Schedule schedule;
    
    // 所有已注册的异步步进模块
    HashSet asynchronous = new HashSet();
    // 用于访问 HashSet 的锁
    Object asynchronousLock = new boolean[1];  // an array is a unique, serializable object
    // 我们正在清理并替换这个 HashSet 吗？
    boolean cleaningAsynchronous = false;

    //内部构造方法
    SimState(long seed, MersenneTwisterFast random, Schedule schedule)
        {
        this.random = random;
        this.schedule = schedule;
        this.seed = (int) seed;   // 随机种子，强制转换为 32 位，因为无论如何 MTF 都会使用 32 位
        }

    /** 创建一个 SimState 实例：使用初始化到指定种子的新随机数生成器，外加一个全新的空调度器。 */
    //传入随机种子
    public SimState(long seed)
        {
        this(seed, new MersenneTwisterFast(seed), new Schedule());
        }
    
    /** 使用指定的随机数生成器和调度器创建一个 SimState 实例，并将种子设置为无效值（0）。此方法仅应被需要使用现有随机数生成器和调度器的 SimState 子类调用。*/
    //传入随机数生成器和调度器，随机种子无效
    protected SimState(MersenneTwisterFast random, Schedule schedule)
        {
        this(0, random, schedule);  // 0 is a bogus value.  In fact, MT can't have 0 as its seed value.
        }
                
    /** 使用指定调度器创建 SimState，并新建一个随机数生成器。此方法仅应被需要使用现有调度器的 SimState 子类调用。*/
    //传入随机种子和调度器
    protected SimState(long seed, Schedule schedule)
        {
        this(seed, new MersenneTwisterFast(seed), schedule);
        }

    /** 使用新建的调度器、传入的随机数生成器创建 SimState，并设置无效种子（0）。此方法仅应被需要使用现有随机数生成器的 SimState 子类调用。*/
    //传入随机数生成器
    protected SimState(MersenneTwisterFast random)
        {
        this(0, random, new Schedule());  // 0 is a bogus value.  In fact, MT can't have 0 as its seed value.
        }



    public void setSeed(long seed)
        {
        seed = (int) seed;  // force to 32 bits since that's what MTF will be using anyway
        random = new MersenneTwisterFast(seed);
        this.seed = seed;
        }


    //随机数生成器的安全预热机制
    /** 初始化生成器。梅森旋转算法会使用一个基础的线性同余生成器来生成初始的 624 个种子；
     在此之后，它会通过梅森旋转算法生成新的种子。
     最初的这 624 个种子通常是没问题的，但为了额外确保安全性，
     你可以通过调用其 nextInt () 方法若干次（次数为 (N>1) * 624）来初始化生成器。
     本方法正是执行此操作，当前设置 N=2（再加 1 次）。 */
    public static MersenneTwisterFast primeGenerator(MersenneTwisterFast generator)
        {
        // 624 这个魔法数字，对应源码里私有的 N 常量
        for(int i = 0; i < 624 * 2 + 1; i++)
            generator.nextInt();
        return generator;
        }

    /** 在启动仿真前，或多次仿真运行之间立即调用。
     这让你有机会进行初始化设置，或从上一次仿真运行中重置状态。
     默认实现仅会用一个全新的调度器替换当前调度器。  */
    public void start()
        {
        // 多生成几次随机数把开头不太 “随机” 的阶段跳过去，让后面的结果更均匀、更随机、质量更高。
        random = primeGenerator(random);
        // 保险操作（清理所有已注册的异步步进任务）
        cleanupAsynchronous();
        // 重置调度器
        schedule.reset();
        schedule.clearBeforeAndAfter();
        }
        
    /** 在仿真正常结束或提前终止时被调用。
     若用户直接退出程序，该函数可能不会被执行。
     该方法可能被多次调用。
     若你需要检测这种情况，最简单的方法是：在 start () 方法中设置一个标记位，并在第一次执行 finish () 时清除该标记。 */
    public void finish()
        {
        kill();  // 清理异步任务并重置调度器，完成一次妥善的结束
        }

    /** 调度器上的步进任务（Steppable）可调用此方法来终止仿真。
     所有已存在的异步步进任务（AsynchronousSteppable）都会被停止，随后调度器将被重置。
     异步步进任务、并行序列（ParallelSequence）以及非主线程不应直接调用此方法—— 否则会造成死锁。
     它们可以通过在下一个时间步安排一个调用 state.kill () 的步进任务，来间接终止仿真。. */
    public void kill()
        {
        cleanupAsynchronous();
        schedule.clear();
        schedule.seal();
        }

    /** Registers an AsynchronousSteppable to get its pause() method called prior to checkpointing,
        its resume() method to be called after checkpointing or recovery, and its stop()
        method to be called at finish() time.  The purpose of the addToCleanup() method is to provide
        the simulation with a way of stopping existing threads which the user has created in the background.
        
        <P> An AsynchronousSteppable cannot be added multiple times
        to the same registry -- if it's there it's there.  Returns false if the AsynchronousSteppable could
        not be added, either because the simulation is stopped or in the process of finish()ing.
    */
    public boolean addToAsynchronousRegistry(AsynchronousSteppable stop)
        {
        if (stop==null) return false;
        synchronized(asynchronousLock) 
            { 
            if (cleaningAsynchronous) return false;  
            asynchronous.add(stop);
            return true;
            }
        }
        
    /**
       Unregisters an AsynchronousSteppable from the asynchronous registry.
    */
    public void removeFromAsynchronousRegistry(AsynchronousSteppable stop)
        {
        if (stop==null) return;
        synchronized(asynchronousLock) 
            { 
            if (!cleaningAsynchronous) 
                asynchronous.remove(stop);
            }
        }
        
    /** Returns all the AsynchronousSteppable items presently in the registry.  The returned array is not used internally -- you are free to modify it. */
    public AsynchronousSteppable[] asynchronousRegistry()
        {
        synchronized(asynchronousLock)
            {
            AsynchronousSteppable[] b = new AsynchronousSteppable[asynchronous.size()];
            int x = 0;
            Iterator i = asynchronous.iterator();
            while(i.hasNext())
                b[x++] = (AsynchronousSteppable)(i.next());
            return b;
            }
        }
        
    /*
      Calls all the registered Asynchronnous.  During this period, any methods which attempt to
      register things for the schedule will simply be ignored.  
    */
    // perhaps use a LinkedHashSet instead of a HashSet?
    void cleanupAsynchronous()
        {
        AsynchronousSteppable[] b = null;
        synchronized(asynchronousLock)
            {
            b = asynchronousRegistry();
            cleaningAsynchronous = true;
            }
        final int len = b.length;
        for(int x=0;x<len;x++) b[x].stop();
        synchronized(asynchronousLock) 
            {
            asynchronous = new HashSet(asynchronous.size());
            cleaningAsynchronous = false;
            }
        }


    /** Called just before the SimState is being checkpointed (serialized out to a file to be
        unserialized and fired up at a future time).  You should override this to prepare 
        your SimState object appropriately. Be sure to call super.preCheckpoint(). */
    public void preCheckpoint()
        {
        AsynchronousSteppable[] b = asynchronousRegistry();
        final int len = b.length;
        for(int x=0;x<len;x++) b[x].pause();
        }
    
    /** Called just after the SimState was checkpointed (serialized out to a file to be
        unserialized and fired up at a future time).  You cam override this as you see fit. 
        Be sure to call super.postCheckpoint(). */
    public void postCheckpoint()
        {
        AsynchronousSteppable[] b = asynchronousRegistry();
        final int len = b.length;
        for(int x=0;x<len;x++) b[x].resume(false);
        }

    /** Called after the SimState was created by reading from a checkpointed object.  You should
        set up your SimState in any way necessary (reestablishing file connections, etc.) to fix
        anything that may no longer exist.  Be sure to call super.awakeFromCheckpoint(). */
    public void awakeFromCheckpoint()
        {
        AsynchronousSteppable[] b = asynchronousRegistry();
        final int len = b.length;
        for(int x=0;x<len;x++) b[x].resume(true);
        }

    /** Serializes out the SimState, and the entire simulation state (not including the graphical interfaces)
        to the provided stream. Calls preCheckpoint() before and postCheckpoint() afterwards.
        Throws an IOException if the stream becomes invalid (prematurely closes, etc.).  Does not close or flush
        the stream. */
    public void writeToCheckpoint(OutputStream stream) throws IOException
        {
        preCheckpoint();

        GZIPOutputStream g = 
            new GZIPOutputStream(
                new BufferedOutputStream(stream));

        ObjectOutputStream s = 
            new ObjectOutputStream(g);
            
        s.writeObject(this);
        s.flush();
        g.finish();  // need to force out the gzip stream AND manually flush it.  Java's annoying.  Took a while to find this bug...
        g.flush();
        postCheckpoint();
        }
    
    /** Writes the state to a checkpoint and returns the state.
        If an exception is raised, it is printed and null is returned. */
    public SimState writeToCheckpoint(File file)
        {
        FileOutputStream f = null;
        try {
            f = new FileOutputStream(file);
            writeToCheckpoint(f);
            f.close();
            return this;
            }
        catch(Exception e) 
            {
            try { if (f != null) f.close(); } catch (Exception e2) { }
            e.printStackTrace(); 
            return null; 
            }
        }
    
    /** Creates a SimState from checkpoint.  If an exception is raised, it is printed and null is returned. */
    public static SimState readFromCheckpoint(File file)
        {
        try {
            FileInputStream f = new FileInputStream(file);
            SimState state = readFromCheckpoint(f);
            f.close();
            return state;
            }
        catch(Exception e) { e.printStackTrace(); return null; }
        }

    /** Creates and returns a new SimState object read in from the provided stream.  Calls awakeFromCheckpoint().
        Throws an IOException if the stream becomes invalid (prematurely closes etc.).  Throws a ClassNotFoundException
        if a serialized object is not found in the CLASSPATH and thus cannot be created.  Throws an OptionalDataException
        if the stream is corrupted.  Throws a ClassCastException if the top-level object is not actually a SimState.
        Does not close or flush the stream. */
    public static SimState readFromCheckpoint(InputStream stream)
        throws IOException, ClassNotFoundException, OptionalDataException, ClassCastException
        {
        ObjectInputStream s = 
            new ObjectInputStream(
                new GZIPInputStream (
                    new BufferedInputStream(stream)));
        SimState state = (SimState) (s.readObject());
        state.awakeFromCheckpoint();
        return state;
        }
    
    protected static boolean keyExists(String key, String[] args)
        {
        for(int x=0;x<args.length;x++)
            if (args[x].equalsIgnoreCase(key))
                return true;
        return false;
        }

    protected static String argumentForKey(String key, String[] args)
        {
        for(int x=0;x<args.length-1;x++)  // if a key has an argument, it can't be the last string
            if (args[x].equalsIgnoreCase(key))
                return args[x + 1];
        return null;
        }
    
    long job = 0;
    long seed = 0;  // 被视为无效值
        
    /** Returns the seed set by the doLoop(...) facility and by the constructor.
        Only to be used for GUIs to display possible seed values.  */
    public long seed()
        {
        return (int) seed;
        }

    public void setJob(long job)
        {
        this.job = job;
        }
        
    /** Returns the job number set by the doLoop(...) facility.  This number
        is not incremented by the GUI. */
    public long job()
        {
        return job;
        }

	/**
     与 doLoop(c, args, true) 功能完全相同
	*/
    public static void doLoop(final Class c, String[] args) 
    	{
    	doLoop(c, args, true);
    	}

    /**
     调用 doLoop(MakesSimState, args) 方法，并传入一个 MakesSimState 对象。
     该对象通过构造方法 new <simState>(<随机种子>) 创建指定类 c 的 SimState 实例。
     如果 exit 参数为 true，则 doLoop() 执行完毕后会直接退出 Java 程序。*/
    public static void doLoop(final Class c, String[] args, boolean exit) 
        {
        doLoop(new MakesSimState() 
            {
                /**给一个 仿真类（如 HelloWorld）
                 会自动找到它的构造方法：new HelloWorld(long seed)
                 然后自动创建一个对象：new HelloWorld(seed)
                 最后返回这个对象给 doLoop 使用*/
            public SimState newInstance(long seed, String[] args) 
                {
                try {
                    return (SimState)(c.getConstructor(
                            new Class[] { Long.TYPE }  //找参数是 long 型的构造方法
                    ).newInstance(
                            new Object[] { Long.valueOf(seed) }  //传入 seed
                    ));
                    }
                catch (Exception e) 
                    {
                    if (e instanceof InvocationTargetException)
                        e.getCause().printStackTrace();
                    throw new RuntimeException("Exception occurred while trying to construct the simulation " + c, e);
                    }
                }
            /**返回传入的仿真类，给外部查看用的。*/
                                
            public Class simulationClass() { return c; }
            /**返回构造方法，给 GUI、启动器用的。*/

            public Constructor[] getConstructors() { return c.getConstructors(); }
            }, args, exit);
        }



    /**作用等同于 doLoop (generator, args, true);    */
    public static void doLoop(final MakesSimState generator, final String[] args)
		{
		doLoop(generator, args, true);
		}
		
    /** 为模拟命令行提供的便捷顶层循环。
     接收一个 MakesSimState 对象（该对象负责提供用于运行模拟的 SimState），以及应用程序的参数列表 args。
     此循环可实现：
        <ul>
        <li> 多次重复执行任务
        </ul>
     若 exit 为 true，则 doLoop() 执行完成后会退出 Java 程序。
    */
    public static void doLoop(final MakesSimState generator, final String[] args, final boolean exit)
        {
        // 是否输出帮助信息
        if (keyExists("-help", args))
            {
            System.err.println(
                "Format:           java " + generator.simulationClass().getName() + " \\\n" +
                "                       [-help] [-repeat R] [-parallel P] [-seed S] \\\n" +
                "                       [-until U] [-for F] [-time T] [-docheckpoint D] \\\n" +
                "                       [-checkpointname N] [-checkpoint C] [-quiet] \n\n" +
                "-help             Shows this message and exits.\n\n" +
                "-repeat R         Long value > 0: Runs R jobs.  Unless overridden by a\n" +
                "                  checkpoint recovery (see -checkpoint), the random seed for\n" +
                "                  each job is the provided -seed plus the job# (starting at 0).\n" +
                "                  Default: runs once only: job number is 0.\n\n" +
                "-parallel P       Long value > 0: Runs P separate batches of jobs in parallel,\n" +
                "                  each one containing R jobs (as specified by -repeat).  Each\n" +
                "                  batch has its own independent set of checkpoint files.  Job\n" +
                "                  numbers are 0, P, P*2, ... for the first batch, then 1, P+1,\n" +
                "                  P*2+1, ... for the second batch, then 2, P+2, P*2+2, ... for\n" +
                "                  the third batch, and so on.  -parallel may not be used in\n" +
                "                  combination with -checkpoint.\n" +
                "                  Default: one batch only (no parallelism).\n\n" +
                "-seed S           Long value not 0: the random number generator seed, unless \n" +
                "                  overridden by a checkpoint recovery (see -checkpoint).\n" +
                "                  Default: the system time in milliseconds.\n\n" +
                "-until U          Double value >= 0: the simulation must stop when the\n" +
                "                  simulation time U has been reached or exceeded.\n" +
                "                  If -for is also included, the simulation terminates when\n" + 
                "                  either of them is completed.\n" + 
                "                  Default: don't stop.\n\n" +
                "-for N            Long value >= 0: the simulation must stop when N\n" +
                "                  simulation steps have transpired.   If -until is also\n" +
                "                  included, the simulation terminates when either of them is\n" + 
                "                  completed.\n" +
                "                  Default: don't stop.\n\n" +
                "-time T           Long value >= 0: print a timestamp every T simulation steps.\n" +
                "                  If 0, nothing is printed.\n" +
                "                  Default: auto-chooses number of steps based on how many\n" +
                "                  appear to fit in one second of wall clock time.  Rounds to\n" +
                "                  one of 1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, etc.\n\n" +
                "-docheckpoint D   Long value > 0: checkpoint every D simulation steps.\n" +
                "                  Default: never.\n" +
                "                  Checkpoint files named       <steps>.<job#>.NAME.checkpoint\n" +
                "                  where NAME is specified in -checkpointname\n\n" +
                "-checkpointname N String: id for the checkpoint filename (see -docheckpoint)\n" +
                "                  Default: " + 
                generator.simulationClass().getName().substring(generator.simulationClass().getName().lastIndexOf(".") + 1) + 
                "\n\n" + 
                "-checkpoint C     String: loads the simulation from file C, recovering the job\n" +
                "                  number and the seed.  If the checkpointed simulation was begun\n" +
                "                  on the command line but was passed through the GUI for a while\n" +
                "                  (even multiply restarted in the GUI) and then recheckpointed,\n" +
                "                  then the seed and job numbers will be the same as when they\n" +
                "                  were last on the command line.  If the checkpointed simulation\n" +
                "                  was begun on the GUI, then the seed will not be recovered and\n"+
                "                  job will be set to 0. Further jobs and seeds are incremented\n" +
                "                  from the recovered job and seed.\n" +
                "                  Default: starts a new simulation rather than loading one, at\n" +
                "                  job 0 and with the seed given in -seed.\n\n" + 
                "-quiet            Does not print messages except for errors and warnings.\n" + 
                "                  This option implies -time 0.\n" +
                "                  Default: prints all messages.\n"
                );
            System.exit(0);
            }

        final boolean quiet = keyExists("-quiet", args);

        java.text.NumberFormat n = java.text.NumberFormat.getInstance();
        n.setMinimumFractionDigits(0);
        if (!quiet) System.err.println("MASON Version " + n.format(version()) + ".  For further options, try adding ' -help' at end.");

        // 计算检查点取模值
        double _until = Double.POSITIVE_INFINITY;
        String until_s = argumentForKey("-until", args);
        if (until_s != null)
            try
                {
                _until = Double.parseDouble(until_s);
                if (_until < 0.0) throw new Exception();
                }
            catch (Exception e)
                {
                throw new RuntimeException("Invalid 'until' value: " + until_s + ", must be a positive real value");
                }
        final double until = _until; // stupid Java

        long _seed = System.currentTimeMillis();
        String seed_s = argumentForKey("-seed", args);
        if (seed_s != null)
            try
                {
                _seed = Long.parseLong(seed_s);
                if (_seed == 0) throw new Exception();
                }
            catch (Exception e)
                {
                throw new RuntimeException("Invalid 'seed' value: " + seed_s + ", must be a non-zero integer, or nonexistent to seed by clock time");
                }
        final long seed_init = _seed;  // grrrr
        
        long __for = -1;
        String for_s = argumentForKey("-for", args);
        if (for_s != null)
            try
                {
                __for = Long.parseLong(for_s);
                if (__for < 0) throw new Exception();
                }
            catch (Exception e)
                {
                throw new RuntimeException("Invalid 'for' value: " + for_s + ", must be an integer >= 0");
                }
        final long _for = __for;  // argh
        
        long _time = -1;
        String time_s = argumentForKey("-time", args);
        if (time_s != null)
            try
                {
                _time = Long.parseLong(time_s);
                if (_time < 0) throw new Exception();
                }
            catch (Exception e)
                {
                throw new RuntimeException("Invalid 'time' value: " + time_s + ", must be a positive integer");
                }
        final long time_init = _time;  //blah
        
        final String checkpointName = argumentForKey("-checkpointname", args);

        long _cmod = 0;
        String cmod_s = argumentForKey("-docheckpoint", args);
        if (cmod_s != null)
            try
                {
                _cmod = Long.parseLong(cmod_s);
                if (_cmod <= 0) throw new Exception();
                }
            catch (Exception e)
                {
                throw new RuntimeException("Invalid checkpoint modulo: " + cmod_s + ", must be a positive integer");
                }
        final long cmod = _cmod;
        
        long _repeat = 1;
        String repeat_s = argumentForKey("-repeat", args);
        if (repeat_s != null)
            try
                {
                _repeat = Long.parseLong(repeat_s);
                if (_repeat <= 0) throw new Exception();
                }
            catch (Exception e)
                {
                throw new RuntimeException("Invalid repeat value: " + repeat_s + ", must be a positive integer");
                }
        final long repeat = _repeat;
       
        int _parallel = 1;
        String parallel_s = argumentForKey("-parallel", args);
        if (parallel_s != null)
            try
                {
                _parallel = Integer.parseInt(parallel_s);
                if (_parallel <= 0) throw new Exception();
                }
            catch (Exception e)
                {
                throw new RuntimeException("Invalid parallel value: " + parallel_s + ", must be a positive integer");
                }
        final int parallel = _parallel;
                
        //  在启用检查点的前提下，检查程序是否处于并行运行状态
        final String checkpointFile = argumentForKey("-checkpoint", args);
        if (parallel > 1 && checkpointFile != null)
            {
            System.err.println("Cannot load from checkpoint and run in parallel at the same time.  Sorry.");
            System.exit(1);
            }
       
       
        // 好了，现在我们正式进入正题 / 开始干正事。
        
        // 注意，这段逻辑有点混乱——即便只需要运行一个线程，我们也会新建一个线程。
        // 此外请注意，我们是在线程内部从检查点加载初始任务的。
        // 这很可能会改变任务编号，进而可能与其他线程中的任务编号产生冲突，
        // 因此**仅当只有单个线程时**才允许执行该操作。
        // 我们在上面已经对这种情况进行了检查。


        //创建并行线程
        Thread[] threads = new Thread[parallel];
        for(int _thread = 0; _thread < parallel; _thread++)
            {
            final int thread = _thread;  // stupid Java
            threads[thread] = new Thread(new Runnable()
                {
                    //单个线程跑的仿真
                public void run()
                    {
                    long time = time_init - 1;
                    long job = thread * repeat;
                    long seed = seed_init + job;  // initially anyway
                    for(long rep = 0 ; rep < repeat; rep++)
                        {
                        SimState state = null;
                
                        // 从检查点恢复启动？注意：仅当只有单个线程时才会执行此操作，因此在这里修改任务编号是安全的。
                        if (rep == 0 && checkpointFile!=null)  // 只有任务 0 可以从检查点加载状态
                            {
                            if (!quiet) printlnSynchronized("Loading from checkpoint " + checkpointFile);
                            state = SimState.readFromCheckpoint(new File(checkpointFile));
                            if (state == null)   // 检测到错误且已输出提示，程序直接退出。
                                System.exit(1);
                            else if (state.getClass() != generator.simulationClass())  // 啊哦，文件中存储的模拟程序不匹配！
                                {
                                printlnSynchronized("Checkpoint contains some other simulation: " + state + ", should have been of class " + generator.simulationClass());
                                System.exit(1);
                                }
                                                                                
                            job = state.job();
                            if (state.seed() != 0) // 当前使用的随机种子，是之前通过命令行传入的、可用的有效值。
                                {
                                seed = state.seed();
                                if (!quiet) printlnSynchronized("Recovered job: " + state.job() + " Seed: " + state.seed());
                                }
                            else if (!quiet) printlnSynchronized("Renamed job: " + state.job() + " (unknown seed)");
                            }

                        // ... 还是我们应该重新从头开始运行？
                        if (state==null)  // 未请求使用检查点文件
                            {
                            state = generator.newInstance(seed,args);
                            state.setJob(job);
                            state.setSeed(seed);
                            if (!quiet) printlnSynchronized("Job: " + state.job() + " Seed: " + state.seed());
                            state.start();
                            }

                        //格式化 “仿真运行速度” 的数字，让打印出来的数字好看、整齐、不乱。
                        NumberFormat rateFormat = NumberFormat.getInstance();//创建一个数字格式化工具，专门用来把数字变得好看。
                        rateFormat.setMaximumFractionDigits(5);// 小数最多保留5位
                        rateFormat.setMinimumIntegerDigits(1);// 整数部分至少显示1位

                        // 开始循环
                        boolean retval = false;//标记仿真是不是正常结束
                        long steps = 0;//记录当前跑了多少步，后面会用 schedule.getSteps() 更新
                        long clock;//存当前系统时间戳（毫秒）
                        long oldClock = System.currentTimeMillis();//记录循环开始时的时间
                        Schedule schedule = state.schedule;//取出仿真的调度器
                        long firstSteps = schedule.getSteps();//记录本次任务开始时的步数
                        
                        while((_for == -1 || steps < _for) && schedule.getTime() <= until)
                            {
                            state.preSchedule();//前

                            if (!schedule.step(state))// schedule.step(state)   核心
                                {
                                retval=true; //正常结束
                                break;
                                }

                            state.postSchedule();//后

                            steps = schedule.getSteps();//步数++
                            if (time < 0)  // 没有用 -time 指定打印间隔
                                {
                                if (System.currentTimeMillis() - oldClock > 1000L)  // 已经跑了超过1秒
                                    {
                                    time = figureTime(steps - firstSteps);  // 自动算出一个合适的打印频率
                                    }
                                }
                            if (time > 0 && steps % time == 0)  // 到了该打印的步数
                                {
                                clock = System.currentTimeMillis();
                                // 打印：任务号、当前步数、仿真时间、运行速度
                                if (!quiet) printlnSynchronized("Job " + job + ": " + "Steps: " + steps + " Time: " + state.schedule.getTimestamp("At Start", "Done") + " Rate: " + rateFormat.format((1000.0 *(steps - firstSteps)) / (clock - oldClock)));
                                firstSteps = steps;
                                oldClock = clock;
                                }
                            if (cmod > 0 && steps % cmod == 0)  // 到了该保存检查点的步数
                                {
                                String id = checkpointName;
                                if (id == null)
                                    id = state.getClass().getName().substring(state.getClass().getName().lastIndexOf(".") + 1);
                                //生成文件名：步数.任务号.模型名.checkpoint
                                String s = "" + steps + "." + state.job() +  "." + id  + ".checkpoint";
                                if (!quiet) printlnSynchronized("Job " + job + ": " + "Checkpointing to file: " + s);

                                state.writeToCheckpoint(new File(s));  // 把整个仿真状态存进文件
                                }
                            }
                                
                        state.finish();
                        
                        if (retval) 
                            {
                            if (!quiet) printlnSynchronized("Job " + job + ": " + "Exhausted " + state.job );
                            }
                        else
                            {
                            if (!quiet) printlnSynchronized("Job " + job + ": " + "Quit " + state.job);
                            }

                        job++;
                        seed++;
                        }
                    }
                });

            // 我们本希望线程在运行时自行设置名称，这样它就能加载模型、确定模型类等信息。
            // 但我们无法这么做，因为一旦 Eclipse/NetBeans 的性能分析器检测到某个类，
            // 就不会再允许修改该类的名称了。此时再去改名已经为时已晚。
            // 因此我们只能在线程启动之前，先在这里把名称设置好，没办法啊。

            threads[thread].setName("MASON " + thread);  //给线程起名字
            threads[thread].start();  //启动线程
            }
        //让主线程等着所有仿真线程全部跑完，再继续往下走
        for(int thread = 0; thread < parallel; thread++)
            {
            try { threads[thread].join(); } catch (InterruptedException ex) {  }  // 什么都不做
            }
        //仿真全部结束后，关闭 Java 虚拟机，程序彻底退出。
        if (exit) System.exit(0);
        }
        
    static Object printLock = new Object[0];
    public static void printlnSynchronized(String val)
        {
        synchronized(printLock) { System.err.println(val); }
        }
    
    /** @Deprecated */
    public void nameThread()
        {
        Thread.currentThread().setName("MASON Model: " + this.getClass());
        }

    /** Returns MASON's Version */
    public static double version()
        {
        return 22.0;
        }
    
    /** Hook for distributed MASON, called immediately before stepping the schedule. */
    public void preSchedule()
        {
        // does nothing
        }
        
    /** Hook for distributed MASON, called immediately after stepping the schedule. */
    public void postSchedule()
        {
        // does nothing
        }
    
    // compute how much time per step 
    // it's possible this could go into an infinite loop if time is gigantic
    // but that's not likely.  Otherwise takes O(lg(time)) time, which is
    // reasonable for a long
    static protected long figureTime(long time)
        {
        long n = 1;
        while(true)
            {
            if (n >= time) return n;
            if ((n*10)/4 >= time) return (n*10)/4;
            if ((n*10)/2 >= time) return (n*10)/2;
            n = n*10;
            }
        }
    
    
    /** Override this method to return a multiobjective optimization assessment of
        the simulation run.  You should return an assessment of the model performance
        for each of some N >= 1 objectives (N is the length of the array).  For each
        objective i, the returned assessment[i] should be between 0 (the lowest quality)
        and 1 (the highest quality) inclusive.  The default version of this method simply
        returns all 0, meaning a horrible, no good, very bad simulation run.
        
        <p>This method is only used by the distributed optimization facility
        and will be called, possibly repeatedly, after start() and before finish().  If you're
        not doing distributed optimization, it'll never be called, so you can ignore this method.
        By default the method nonsensically always returns an optimimum score, that is, it 
        suggests that the model is ideally calibrated. 
    */ 
    public double[] assess(int numObjectives)
        {
        double[] assessment = new double[numObjectives];
        for(int i = 0; i < assessment.length; i++)
            {
            assessment[i] = 0.0;
            }
        return assessment;
        }

    /** You only override this method to set parameter values for purposes of optimization: MASON's
        optimization facility (in contrib/optimize) may call this to set up certain parameters in bulk
        prior to running the model and assessing it with assess(...).  Otherwise you don't fool with this. */
    public void setOptimizationParameters(double[] parameterValues)
        {
        // does nothing by default
        }

    PrintWriter writer = null;
        
    /** This method is called to set the PrintWriter used in getDescription() [which is more important to you]. */ 
    public void setDescription(PrintWriter writer)
        {
        this.writer = writer;
        }
    
    /** Returns the PrintWriter used to describe a high-quality assessed SimState.  If getDescription() 
        returns a PrintWriter (as opposed to null), you can use this to write out descriptive statistics,
        indeed anything you like, as the simulation is running to describe it.  This is done at the end
        of optimization, when one or more SimState models, typically the best one discovered, will
        be run one final time with a PrintWriter description.  The description will be set prior to start()
        and should not be saved nor written to after finish().  
    */ 
    public PrintWriter description()
        {
        return writer;
        }
    
    /** Returns true if the simulation is a remote proxy simulation.  Don't override this: 
        it's provided mostly for the distributed visualizer. */
    public boolean remoteProxy()
        {
        return false;
        }
        
    /** Override this to revise the steps displayed in the Console.  Don't override this: 
        it's provided mostly for the distributed visualizer. */
    public long remoteSteps()
        {
        return 0;
        }
    
    /** Override this to revise the time displayed in the Console.  Ordinarily you should leave this
        alone: it's provided mostly for the distributed visualizer. */
    public double remoteTime()
        {
        return 0;
        }

    /** Override this to add a tab to the Console. Normally you'd not do this from the SimState, but the Distributed code needs to use this. */
    public javax.swing.JComponent provideAdditionalTab()
        {
        return null;
        }
    
    /** Override this to add a tab name to the Console. Normally you'd not do this from the SimState, but the Distributed code needs to use this.  */
    public String provideAdditionalTabName()
        {
        return null;
        }
    }

