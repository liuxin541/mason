/*
  Copyright 2006 by Sean Luke and George Mason University
  Licensed under the Academic Free License version 3.0
  See the file "LICENSE" for more information
*/

package sim.display;
import sim.engine.*;
import sim.portrayal.*;
import java.io.*;
import sim.util.*;
import ec.util.*;
import java.util.*;

/** A wrapper for SimState and Schedule which provides additional functionality for
    GUI objects. This wrapper extends the functionality of SimState and
    Schedule through encapsulation, NOT subclassing.  Why?  Because the
    general idea is that SimState, and *anything* which hangs off of it,
    should be serializable out to a checkpoint and not know or care about whether
    or not it's running under a GUI or running headless.
    
    <p>Displays and Controllers (such as Display2D and the Console) do not care about the
    SimState, and make precious few calls to the Schedule.  Instead, they generally only
    work with you through the GUIState class.
    
    <p>GUIState has the same start() and finish() methods as SimState, and indeed
    the default forms of these methods just call start() and finish() on the underlying
    SimState.  Additionally, GUIState has init(Controller) and quit() methods.  The
    init(Controller) method sets up the GUIState to work in an environment controlled
    by the specified Controller.  The quit() method is called to tell the GUIState to
    shut down and free any of its resources (perhaps the simulation document is being
    closed in the GUI, or the GUI is quitting).  
    
    <p>GUIState also has two methods used by the Controller to specify things about it.
    In particular, <tt>public static String getName(Class class)</tt>
    should return an intelligent name for the simulation,
    and <tt>public static Object getInfo(Class class)</tt> should return an
    HTML or textual description of the simulation either as a URL or a String.
    
    <p>You can create a global inspector for your model (as opposed to the individual
    per-object inspectors created by various portrayals).  This is done by overriding either 
    getInspector() or getSimulationInspectedObject().
    
    <p>GUIState has a wrapper step() method which in turn calls the Schedule's
    step(state) method.  However, this wrapper lso provides a hook for objects such as
    displays to schedule themselves without using the Schedule.  This hook is the
    scheduleImmediate(...) and scheduleImmediateRepeat(...) methods.  There is also
    a reset() method which resets both the immediates and the underlying Schedule.
    
    <p>Last, GUIState has a wrapper function to make it convenient to read in a new SimState from
    a serialized file: readNewStateFromCheckpoint().  This function checks to see if the
    serialized file is valid for this simulation.  To do this, it calls validSimState(),
    which is a hook that you should override to return TRUE if the provided state is a valid SimState
    for your simulation (usually this means that it's the right subclass of SimState for your
    purposes).
    
    <p> Generally speaking, if you have access to a GUIState, you should use GUIstate methods
    start(), finish(), step(), reset(), and readNewStateFromCheckpoint() 
    instead of the underlying methods in the SimState and Controller.  Otherwise, feel free
    to use the underlying methods (such as Schedule.getTime()).
    
    <p><b>Exception Handling</b>.  It's a common error to schedule a null event, or one with an invalid time.
    Like Schedule, GUIState previously returned false or null in such situations, 
    but this leaves the burden on the programmer to check,
    and programmers are forgetful!  We have changed GUIState and Schedule to throw exceptions by default instead.  
    You can change them both back to returning false or null (perhaps if you want to handle the 
    situations yourself more efficiently than catching an exception, or if you know what you're doing schedule-wise)
    by setting Schedule.setThrowingScheduleExceptions(false).
*/

public abstract class GUIState
    {
    /**提供一个额外的随机数生成器，专用于图形用户界面（GUI）和绘图场景，
     与模型中使用的随机数生成器相互独立。若你使用该生成器完成
     诸如指定屏幕上智能体颜色这类操作，而非使用模型自带的生成器，
     即可保证无论程序是在模型模式还是图形界面模式下运行，
     模型都能得到完全一致的仿真结果。
     此外，与 state.random 不同，使用 guirandom
     无需先基于 state.schedule 进行同步操作。 */
    public MersenneTwisterFast guirandom = new MersenneTwisterFast();  //  新定义一个随机数生成器
        
    /**  基础 SimState 实例 */
    public SimState state;
    
    /** GUIState 的控制器。如果不存在控制器，或暂未创建控制器，该字段值可能为 null。 */
    public Controller controller;

    /**storage 是 GUIState 给你预留的一个「通用临时数据盒子」，
     专门用来存和仿真核心无关、但 GUI 界面需要用到的临时数据  ，
     不污染 SimState，也不会被序列化保存。 */
    public HashMap storage = new HashMap();

    /** 请在你的子类中重写此构造方法，调用 <code>super(state)</code>
     （其中 state 是为你的问题场景正确构建好的 SimState 实例）
     切勿调用 <code>super()</code>。*/
    private GUIState()
        {
        }
    
    /** 你可以选择重写此构造方法来调用 <code>super(state)</code>，
        但必须按照规定，重写无参的 GUIState () 构造方法。 */
    public GUIState(SimState state)
        {
        this.state = state;
        resetQueues();  //  初始化清理方法
        }
                
    /** 返回类的短名称。如果类的完整路径是 foo.bar.baz.Quux，则返回 Quux。 */
    public static String getTruncatedName(Class theClass)
        {
        // do the default
        if (theClass==null) return "";
        String fullName = theClass.getName();
        int lastPeriod = fullName.lastIndexOf(".");
        return fullName.substring(lastPeriod + 1);  // nifty, works even if lastPeriod = -1
        }
    
    /** 调用此方法可获取任意类的仿真名称。
        如果某个类未实现 getName () 方法，
        则会使用该类的全限定类名作为替代。
        若要为你的类提供一个易懂的描述性名称，请重写 getName () 方法。 */
                
    public final static String getName(Class theClass)
        {
        if (theClass == null) return "";
        try
            {
            java.lang.reflect.Method m = theClass.getMethod("getName", (Class[])null);
            if (m.getDeclaringClass().equals(GUIState.class)) // it wasn't overridden
                return getTruncatedName(theClass);
            else return (String)(m.invoke(null,(Object[])null));
            }
        catch (NoSuchMethodException e)
            {
            e.printStackTrace();
            return getTruncatedName(theClass);
            }
        catch (Throwable e)
            {
            e.printStackTrace();
            return "Error in retrieving simulation name";
            }
        }
        
    /** 创建并返回一个可供用户操作的控制器（已就绪状态）。
     默认情况下，该方法会创建一个控制台（Console），设置为可见状态并返回。
     你可以重写此方法，以提供其他类型的控制器。 */
    public Controller createController()
        {
        Console console = new Console(this);
        console.setVisible(true);
        return console;
        }
        
    /** Override this method in your subclass to provide a descriptive 
        name for your simulation;
        otherwise the default will be used: the short classname (that is,
        if your class is foo.bar.Baz, Baz will be used).  That might not
        be very descriptive!
                        
        <p>Notice that this is a static method, and yet you're overriding it as if it
        were an instance method.  This is because the method <b>getInfo(Class)</b>
        uses reflection to call the proper method on a per-class basis.  Like magic!
    */
    public static String getName() 
        {
        return "This is GUIState's getName() method.  It probably shouldn't have been called.";
        }

    static Object doDefaultInfo(Class theClass)
        {
        java.net.URL url = theClass.getResource("index.html");
        if (url == null)
            return "<html><head><title></title></head><body bgcolor=\"white\"></body></html>";
        else return url;
        }
                
    /** Returns either a String or a URL which provides descriptive information about the simulation hosted by
        the given class (which should be a GUIState subclass).  To change the information string about your
        own simulation, override the <b>getInfo()</b> method instead.  If you don't override this method,
        MASON will look for a file called "index.html" in the same directory as the class file and use that.
        However if this then fails, Java will hunt for <i>any</i> "index.html" file and use that instead --
        that's probably not what you wanted.  Long story short, either override the getInfo() method,
        or provide an "index.html" file.
    */
    public final static Object getInfo(Class theClass)
        {
        if (theClass == null) return "";
        try
            {
            java.lang.reflect.Method m = theClass.getMethod("getInfo", (Class[])null);
            if (m.getDeclaringClass().equals(GUIState.class)) // it wasn't overridden
                return doDefaultInfo(theClass);
            else return m.invoke(null,(Object[])null);
            }
        catch (NoSuchMethodException e)
            {
            return doDefaultInfo(theClass);
            }
        catch (Throwable e)
            {
            e.printStackTrace();
            return "Error in retrieving simulation info";
            }
        }
        
    /** Override this method with a static method of your own 
        in your subclass to provide an object (a URL or a String) describing information about
        your simulation;  if you do not override this method, then the system will
        look for a file called <tt>index.html</tt> located next to your <tt>.class</tt> file
        and use a URL to that file as the information.  If there is no such file, Java may then go
        on a hunting expedition to find and return some file called <tt>index.html</tt> in your CLASSPATH;
        this is probably not what you intended, but we can't easily prevent it.  If there's absolutely no
        such file, getInfo() will return a blank HTML page.
                
        <p>Notice that this is a static method, and yet you're overriding it as if it
        were an instance method.  This is because the method <b>getInfo(Class)</b>
        uses reflection to call the proper method on a per-class basis.  Like magic!
    */
    public static Object getInfo()
        {
        return "This is GUIState's getInfo() method.  It probably shouldn't have been called.";
        }
        
    /** 重写此方法以向你的仿真提供自定义属性（Properties）对象。
     该操作应当极为少见：默认情况下此方法返回 null。*/
    public sim.util.Properties getSimulationProperties()
        {
        return null;
        }
        /** 返回一个数值，限制 GUI 检查器最多能显示多少个属性，防止面板因属性过多而卡顿、溢出。*/
    public int getMaximumPropertiesForInspector() { return SimpleInspector.DEFAULT_MAX_PROPERTIES; }

    /** 默认情况下，该方法返回一个非易失性检查器（Inspector），该检查器包装了 getSimulationInspectedObject () 方法的返回对象；
     如果 getSimulationInspectedObject () 返回 null，那么 getInspector () 也会返回 null。
     你可以根据需要重写此方法，以提供自定义的检查器。
     需要个性化界面时才重写。*/
    public Inspector getInspector()
        {
        Object object = getSimulationInspectedObject();
        if (object != null)
            {
            Inspector i = Inspector.getInspector(object, this, null);  // will use getMaximumPropertiesForInspector() 
            i.setVolatile(false);
            return i;
            }
        sim.util.Properties prop = getSimulationProperties();
        if (prop != null)
            {
            Inspector i = new SimpleInspector(prop, this, null);    // will use getMaximumPropertiesForInspector() 
            i.setVolatile(true);  // dynamic properties like this are likely volatile
            return i;
            }
        return null;
        }
    
    /** Returns an object with various property methods (getFoo(...), isFoo(...), setFoo(...)) whose
        properties will be accessible by the user.  This gives you an easy way to allow the user to
        set certain global properties of your model from the GUI.   If null is returned (the default),
        then nothing will be displayed to the user.  One trick you should know about your object:
        it should be public, as well its property methods, and if it's anonymous, it should not
        introduce any property methods not defined in its superclass.  Otherwise Java's reflection
        API can't access those methods -- they're considered private.  GUIState also supports
        sim.util.Properties's domFoo(...) domain declarations to allow for sliders and pop-up lists.*/
    public Object getSimulationInspectedObject()
        {
        return null;
        }
        
    /** Use Inspector.isVolatile() instead.  This method returns getInspector().isVolatile().
        This is an expensive method and you should not use it.
        @deprecated
    */
    public boolean isInspectorVolatile()
        {
        return getInspector().isVolatile();
        }
    
    /** 该方法用于初始化（显示）窗口等界面元素。
     你可以使用此方法创建窗口，并将其注册到控制器（Controller）中，以便控制器管理窗口的隐藏、显示和移动。
     此方法的默认实现仅执行一行代码：
     this.controller=controller; */
    public void init(Controller controller)
        {
        this.controller = controller;
        }
    
    /** 在仿真即将启动前，或多次仿真运行的间隙，会立即调用此方法。
        通常情况下，你无需重写这个钩子方法。 */
    boolean started = false;
    public void start()
        {
        started = true;
        state.start();
        
        // 如果存在全局检查器（Inspector），则对其进行调度。
        
        // 执行启动队列中的相关任务
        synchronized(state.schedule)
            {
            //  执行启动队列
            //  先备份，再执行。
            Steppable[] _start2 = start2;
            System.arraycopy(start,0,start2,0,startSize);  //  把要执行的任务全部搬到安全数组里。
            int _startSize = startSize;
            startSize = 0;  //  清空任务队列长度。
            
            // 执行启动任务
            for(int x=0;x<_startSize;x++)
                _start2[x].step(state);
            }
        }
        
        
    /** 该方法会在仿真正常结束或提前终止时被调用。
     如果用户直接退出程序，则该方法可能不会被执行。
     通常情况下，你无需重写这个钩子方法。
     若 GUIState 尚未启动或加载，该方法不执行任何操作。 */
    public void finish()
        {
        if (!started) return;  // 无需调用

        synchronized(state.schedule)
            {
            // 执行结束队列
            Steppable[] _finish2 = finish2;
            System.arraycopy(finish,0,finish2,0,finishSize);
            int _finishSize = finishSize;
            finishSize = 0;
            
            // 执行结束任务
            for(int x=0;x<_finishSize;x++)
                _finish2[x].step(state);
            }

        state.finish();
        resetQueues();  //  清空数列
        started = false;
        }

    /** 当用户退出 ** 仿真状态（SimState）时，控制台会调用此方法。
     你可以把通常需要放在终结器（finalizer）** 中的逻辑放在这里 —— 因为终结器的可靠性极差。
     因此，你应该在此处编写关闭相关显示窗口等代码。*/
    public void quit()
        {
        }

    /** This method should be set to return TRUE if state can be validly used -- mostly likely
        all you need to check is that it's the right class for this simulation.  The default
        returns TRUE if state is non-null and the same class as the current state; that's often sufficient. */
    public boolean validSimState(SimState state)
        {
        return (state != null && state.getClass().equals(this.state.getClass()));
        }

    /** 当用户从检查点加载新状态时，控制台会调用此方法。新状态会以参数形式传入。
     你应当重写该方法，且优先调用 super.load(state)，以此重置你的描绘对象等内容，使其适配新状态。
     state.start() 不会被自动调用。因此，所有你在 start() 中处理、且需要为适配新状态而重置的逻辑，都应当在此方法中实现。
     我们建议你对所有 Display2D 对象调用 repaint() 方法。 */
    public void load(SimState state)
        {
        this.state = state;
        started = true;  // 标记仿真已经启动。以防万一
        
        // 如果有全局参数面板（Inspector），就安排它显示。
        
        // 运行启动队列里的任务
        synchronized(state.schedule)
            {
            // 执行启动队列里的所有任务。
            Steppable[] _start2 = start2;  // 拿一个临时数组 _start2
            System.arraycopy(start,0,start2,0,startSize);  // 把启动任务列表复制进去
            int _startSize = startSize;  // 记录任务数量
            startSize = 0;  // 清空原队列
            
            // 循环执行所有 “启动时要运行的任务”
            for(int x=0;x<_startSize;x++)
                _start2[x].step(state);
            }
        }

    /** Loads a new SimState from the provided file.  Do not call this in an unthreadsafe
        situation -- it doesn't check.  Returns false if the state was not valid.  Returns
        various errors if bad things occurred trying to serialize in from the checkpoint. 
        If false is returned or an error is thrown, the old SimState is retained. */
    public boolean readNewStateFromCheckpoint(File file)
        throws IOException, ClassNotFoundException, OptionalDataException, ClassCastException, Exception
        {
        FileInputStream f = new FileInputStream(file);
        SimState state = SimState.readFromCheckpoint(f);
        f.close();
        if (!validSimState(state)) return false;
        finish();  // let it clean up
        load(state);
        return true;
        }



    // the before and after queues
    protected Steppable[] before;
    Steppable[] before2;
    protected int beforeSize;
    protected Steppable[] after;
    Steppable[] after2;
    protected int afterSize;
  
    // the start and finish queues
    Steppable[] start;
    Steppable[] start2;
    int startSize;
    Steppable[] finish;
    Steppable[] finish2;
    int finishSize;
      
    /** Don't call this unless you know what you're doing. */
    protected void resetQueues()
        {
        before = new Steppable[11];
        before2 = new Steppable[11];
        beforeSize = 0;
        after = new Steppable[11];
        after2 = new Steppable[11];
        afterSize = 0;
        start = new Steppable[11];
        start2 = new Steppable[11];
        startSize = 0;
        finish = new Steppable[11];
        finish2 = new Steppable[11];
        finishSize = 0;
        }
        
    /** Empties out the schedule and resets it to a pristine state BEFORE_SIMULATION.
        If you're using a GUIState, you should call this version instead of Schedule's
        version. 
        @deprecated.  Do not use.
    */
    private synchronized final void reset(SimState state)
        {
        state.schedule.reset();
        resetQueues();
        }
        
    /** 当调度器没有任务可执行，或设定时间用完时，该方法会返回 FALSE。 */
    public boolean step()
        {
        boolean returnval = false;  //   返回值（函数执行完后返回的结果）
        synchronized(state.schedule)
            {
                // 获取【操作前】和【操作后】的队列，
                // 确保在我们使用这些队列期间，其他对象无法向其中添加元素。
            Steppable[] _before2 = before2;  //  保存当前的操作前任务数组，防止后面被覆盖或修改。
            Steppable[] _after2 = after2;  //  保存当前的操作后任务数组，和上面一样是临时备份。
            System.arraycopy(before,0,before2,0,beforeSize);  // 将 before 数组的内容复制到 before2 数组
            System.arraycopy(after,0,after2,0,afterSize);  // 将 after 数组的内容复制到 after2 数组
            int _beforeSize = beforeSize;  //  记录有多少个前置任务需要执行
            int _afterSize = afterSize;  //  记录有多少个后置任务需要执行
            afterSize = 0;
            beforeSize = 0;  //  清空原队列，让新添加的任务从 0 开始计数，不会影响正在执行的任务。
            
            // 先执行【操作前】的相关逻辑
            for(int x=0;x<_beforeSize;x++)
                _before2[x].step(state);

            // 推进调度器（执行队列中的任务）
            returnval = state.schedule.step(state);

            // 执行【操作后】的任务
            for(int x=0;x<_afterSize;x++)
                _after2[x].step(state);
            }
        return returnval;
        }
        
        
    /** Roughly doubles the array size, retaining the existing elements */
    protected Steppable[] increaseSubsteps(Steppable[] substeps)
        {
        Steppable[] newsubstep = new Steppable[substeps.length*2+1];
        System.arraycopy(substeps,0,newsubstep,0,substeps.length);
        return newsubstep;
        }


    /** Schedules an item to occur (in no particular order) immediately before 
        the schedule is stepped on the next time step (not including blank steps).  
        Pass in FALSE to indicate you want to be immediately BEFORE the next timestep;
        pass in TRUE if you want to be immediately AFTER the next time step (the more common
        situation).  Returns false if the current time is AFTER_SIMULATION or if the event is null.
        
        <p>Why would you use this method?  Primarily to get things scheduled which aren't stored
        in the Schedule itself, so it can be serialized out without them. 
    */
        
    public boolean scheduleImmediatelyBefore(Steppable event)
        {
        return _scheduleImmediate(false, event);
        }
        
    /** Schedules an item to occur (in no particular order) immediately after
        the schedule is stepped on the next time step (not including blank steps).  
        Pass in FALSE to indicate you want to be immediately BEFORE the next timestep;
        pass in TRUE if you want to be immediately AFTER the next time step (the more common
        situation).  Returns false if the current time is AFTER_SIMULATION or if the event is null.
        
        <p>Why would you use this method?  Primarily to get things scheduled which aren't stored
        in the Schedule itself, so it can be serialized out without them. 
    */
        
    public boolean scheduleImmediatelyAfter(Steppable event)
        {
        return _scheduleImmediate(true, event);
        }
        
    /** Schedules an item to occur (in no particular order) immediately before or immediately after
        the schedule is stepped on the next time step (not including blank steps).  
        Pass in FALSE to indicate you want to be immediately BEFORE the next timestep;
        pass in TRUE if you want to be immediately AFTER the next time step (the more common
        situation).  Returns false if the current time is AFTER_SIMULATION or if the event is null.
        
        <p>Why would you use this method?  Primarily to get things scheduled which aren't stored
        in the Schedule itself, so it can be serialized out without them. 

        @deprecated use scheduleImmediatelyBefore and scheduleImmediatelyAfter instead
    */
        
    public boolean scheduleImmediate(boolean immediatelyAfter, Steppable event)
        {
        return _scheduleImmediate(immediatelyAfter, event);
        }
                
    boolean _scheduleImmediate(boolean immediatelyAfter, Steppable event)
        {
        synchronized(state.schedule)
            {
            if (event == null || state.schedule.getTime() >= Schedule.AFTER_SIMULATION)
                {
                if (event == null)
                    {
                    throw new IllegalArgumentException("The provided Steppable is null");
                    }
                else if (state.schedule.getTime() >= Schedule.AFTER_SIMULATION)
                    {
                    throw new IllegalArgumentException("The simulation is over and the item cannot be scheduled.");
                    }
                }
            if (immediatelyAfter)
                {
                if (afterSize == after.length)
                    {
                    after = increaseSubsteps(after);
                    after2 = new Steppable[after.length];
                    }
                after[afterSize++] = event;
                }
            else
                {
                if (beforeSize == before.length)
                    {
                    before = increaseSubsteps(before);
                    before2 = new Steppable[before.length];
                    }
                before[beforeSize++] = event;
                }
            }
        return true;
        }
    
    /** Schedules an item to occur (in no particular order) immediately before
        all future steps the Schedule takes (not including blank steps).  
        Pass in FALSE to indicate you want to be immediately BEFORE the next timestep;
        pass in TRUE if you want to be immediately AFTER the next time step (the more common
        situation).  Returns a Stoppable, or null if the current time is AFTER_SIMULATION or if the event is null.
        The recurrence will continue until state.schedule.getTime() >= AFTER_SIMULATION, state.schedule is cleared out,
        or the Stoppable's stop() method is called, whichever happens first.
        
        <p>Why would you use this method?  Primarily to get things scheduled which aren't stored
        in the Schedule itself, so it can be serialized out without them.
    */
        
    public Stoppable scheduleRepeatingImmediatelyBefore(Steppable event)
        {
        return _scheduleImmediateRepeat(false, event);
        }
        

    /** Schedules an item to occur (in no particular order) immediately after
        all future steps the Schedule takes (not including blank steps).  
        Pass in FALSE to indicate you want to be immediately BEFORE the next timestep;
        pass in TRUE if you want to be immediately AFTER the next time step (the more common
        situation).  Returns a Stoppable, or null if the current time is AFTER_SIMULATION or if the event is null.
        The recurrence will continue until state.schedule.getTime() >= AFTER_SIMULATION, state.schedule is cleared out,
        or the Stoppable's stop() method is called, whichever happens first.
        
        <p>Why would you use this method?  Primarily to get things scheduled which aren't stored
        in the Schedule itself, so it can be serialized out without them.
    */
        
    public Stoppable scheduleRepeatingImmediatelyAfter(Steppable event)
        {
        return _scheduleImmediateRepeat(true, event);
        }
        
    /** Schedules an item to occur (in no particular order) immediately before or immediately after
        all future steps the Schedule takes (not including blank steps).  
        Pass in FALSE to indicate you want to be immediately BEFORE the next timestep;
        pass in TRUE if you want to be immediately AFTER the next time step (the more common
        situation).  Returns a Stoppable, or null if the current time is AFTER_SIMULATION or if the event is null.
        The recurrence will continue until state.schedule.getTime() >= AFTER_SIMULATION, state.schedule is cleared out,
        or the Stoppable's stop() method is called, whichever happens first.
        
        <p>Why would you use this method?  Primarily to get things scheduled which aren't stored
        in the Schedule itself, so it can be serialized out without them.

        @deprecated use scheduleRepeatingImmediatelyBefore and scheduleRepeatingImmediatelyAfter instead
    */
        
    public Stoppable scheduleImmediateRepeat(boolean immediatelyAfter, Steppable event)
        {
        return _scheduleImmediateRepeat(immediatelyAfter, event);
        }
        
    Stoppable _scheduleImmediateRepeat(boolean immediatelyAfter, Steppable event)
        {
        Repeat r = new Repeat(immediatelyAfter, event);
        if (scheduleImmediate(immediatelyAfter, r)) return r;
        else return null;
        }

    /** Schedules an item to occur when the user starts or stops the simulator, or when it stops on its own accord.
        If atEnd is TRUE, then the item is scheduled to occur when the finish() method is executed.  If
        atEnd is FALSE, then the item is scheduled to occur when the start() method is executed.
        Returns true if scheduling succeeded.
        
        @deprecated use scheduleAtStart and scheduleAtEnd instead
    */
    public boolean scheduleAtExtreme(Steppable event, boolean atEnd)
        {
        return _scheduleAtExtreme(event, atEnd);
        }
    
    // this has been pulled out to avoid deprecation warnings for scheduleAtStart and scheduleAtEnd
    boolean _scheduleAtExtreme(Steppable event, boolean atEnd)
        {
        synchronized(state.schedule)
            {
            if (event == null || state.schedule.getTime() >= Schedule.AFTER_SIMULATION)
                {
                if (event == null)
                    {
                    throw new IllegalArgumentException("The provided Steppable is null");
                    }
                else if (state.schedule.getTime() >= Schedule.AFTER_SIMULATION)
                    {
                    throw new IllegalArgumentException("The simulation is over and the item cannot be scheduled.");
                    }
                }
            if (atEnd)
                {
                if (finishSize == finish.length)
                    {
                    finish = increaseSubsteps(finish);
                    finish2 = new Steppable[finish.length];
                    }
                finish[finishSize++] = event;
                }
            else
                {
                if (startSize == start.length)
                    {
                    start = increaseSubsteps(start);
                    start2 = new Steppable[start.length];
                    }
                start[startSize++] = event;
                }
            }
        return true;
        }
        
    /** Schedules an item to occur when the user starts the simulator (when the start() method is executed) or loads one (when load() is executed).  Identical to scheduleAtExtreme(event,false). Returns true if scheduling succeeded. 
     */
    public boolean scheduleAtStart(Steppable event)
        {
        return _scheduleAtExtreme(event,false);
        }
        
    /** Schedules an item to occur when the user stops the simulator (when the stop() method is executed), when it stops on its own accord, or when the user has load()ed another simulation to replace it.  Identical to scheculeAtExtreme(event,false).  Returns true if scheduling succeeded. 
     */
    public boolean scheduleAtEnd(Steppable event)
        {
        return _scheduleAtExtreme(event,true);
        }
        

    /** Handles repeated steps.  This is done by wrapping the Steppable with a Repeat object
        which is itself Steppable, and on its step calls its subsidiary Steppable, then reschedules
        itself.  Repeat is stopped by setting its subsidiary to null, and so the next time it's
        scheduled it won't reschedule itself (or call the subsidiary).   A private class for
        GUIState.  Notice almost exactly the same as Schedule.Repeat.  */
    
    class Repeat implements Steppable, Stoppable
        {
        protected boolean immediatelyAfter;
        protected Steppable step;  // if null, does not reschedule
        
        public Repeat(boolean immediatelyAfter, Steppable step)
            {
            this.immediatelyAfter = immediatelyAfter;
            this.step = step;
            }
        
        public synchronized void step(SimState state)
            {
            if (step!=null)
                {
                try
                    {
                    scheduleImmediate(immediatelyAfter,this);
                    }
                catch (IllegalArgumentException e) { /* Only occurs if time has run out */}
                step.step(state);
                }
            }
        
        public synchronized void stop()  
            {
            step = null;
            }
        }
    }
    
