/*
  Copyright 2006 by Sean Luke and George Mason University
  Licensed under the Academic Free License version 3.0
  See the file "LICENSE" for more information
*/

package sim.engine;
import java.io.Serializable;

import sim.util.*;
import ec.util.*;

/**
   Schedule defines a threadsafe scheduling queue in which events can be scheduled to occur
   at future time.  The time of the most recent event which has already occured
   is given by the <b>getTime()</b> method.  If the current time is <tt>BEFORE_SIMULATION</tt> (defined
   to be <tt>EPOCH - 1</tt>),
   then the schedule is set to the "time before time" (the schedule hasn't started running
   yet).  If the current time is <tt>AFTER_SIMULATION</tt> (positive infinity), then the schedule has run
   out of time.  <tt>EPOCH</tt> (0.0) is defined as the first timestep for which you can legally schedule a value.
   <tt>EPOCH_PLUS_ESPILON</tt> is defined as the smallest possible second timestep for which you can legally sechedule a value.
   If you're scheduling events to occur on integer timesteps, you may want to ensure that your simulation 
   does not run beyond <tt>MAXIMUM_INTEGER</tt> (9007199254740992L or 9.007199254740992E15).  For values of a 
   double d >= <tt>MAXIMUM_INTEGER</tt>, d + 1 == d !

   <p>An event is defined as a <b>Steppable</b> object. You can schedule events to either 
   occur a single time or to occur repeatedly at some interval.  If the event occurs repeatedly,
   the schedule will provide you with a <b>Stoppable</b> object on which you can call <b>stop()</b>
   to cancel all future repeats of the event.  If instead you wish to "stop" a single-time event from occuring
   before its time has come, you should do so through the use of a <b>TentativeStep</b> object.  At present
   you cannot delete objects from the Schedule -- just stop them and let them drop out in due course.
   
   <p>The schedule is pulsed by calling its <b>step(...)</b> method.  Each pulse, the schedule
   finds the minimum time at which events are scheduled, moves ahead to that time, and then calls
   all the events scheduled at that time.    Multiple events may be scheduled for the same time.
   No event may be scheduled for a time earlier than getTime().  If at time getTime() you schedule a new
   event for time getTime(), then actually this event will occur at time getTime()+epsilon, that is, the
   smallest possible slice of time greater than getTime().

   <p><b>IMPORTANT NOTE:</b> we have disabled the setShuffling() procedure by making the methods private.  The reason for this is that although turning off shuffling causes the Steppables to be stepped in a <i>predictable order</i>, they will not necessarily be stepped in <i>the order in which they were submitted</i>, which was the whole point of the methods.  The reason for this is that a binary heap is not "stable": it doesn't break ties by returning elements in the same order in which they appeared.  This potentially could cause bugs in simulations and we want to make it very clear.
  
   <p>Events at a step are further subdivided and scheduled according to their <i>ordering</i>, an integer.
   Objects for scheduled for lower orderings for a given time will be executed before objects with
   higher orderings for the same time.  If objects are scheduled for the same time and
   have the same ordering value, their execution will be randomly ordered with respect to one another.
   
   <!-- 
   unless (in the very rare case) you have called setShuffling(false);.  Generally speaking, most experiments with
   good model methodologies will want random shuffling left on, and if you need an explicit ordering, it may be
   better to rely on Steppable's orderings or to use a Sequence.
   -->
   
   <p>You might be wondering: why bother with using orderings?  After all, can't you achieve the same thing by just
   stretching elements out in time?  There are two reasons to use orderings.  First, it allows you to use the getTime()
   method to keep tabs on the current time in a way that might be convenient to you.  But second and more importantly,
   MASON's GUI facility will update its displays and inspectors only after all Steppables scheduled for a 
   given timestamp have completed, and so orderings give you a way of subdividing the interval of time between
   GUI updates.
   
   <p>A schedule may be <i>sealed</i> meaning that it will refuse to accept any further scheduled events
   even if its time is not yet AFTER_SIMULATION.  This is largely done internally by MASON code: you probably
   will never want to do this.  Once a schedule is sealed it cannot be unsealed until it is reset().
   
   <p>You can clear out the entire Schedule, unseal it, and restart it to BEFORE_SIMULATION by calling reset().
   However, this does not prevent AsynchronousSteppables from suddenly rescheduling themselves
   in the queue.  Stopping the simulation from within a Steppable object's step() method is best done by
   calling SimState.kill().  From the main thread, the most straightforward way to stop a simulation is to just
   stop calling schedule.step(...), and proceed directly to SimState.finish().
   
   <p>You can get the number of times that step(...) has been called on the schedule by calling the getSteps() method.
   This value is incremented just as the Schedule exits its step(...) method and only if the method returned true.
   Additionally, you can get a string version of the current time with the getTimestamp(...) method.
   
   <p><b>Note on Synchronization</b>.  In order to maximize the ability for threads to access the Schedule at any time, 
   Schedule uses two locks for synchronization.  First, the <b>step() method synchronizes on the Schedule</b> itself.  This
   prevents step() from being called simultaneously from different threads; also step() tests to make sure that it's not
   called reentrantly from within the same thread.  Second, <b>many methods synchronize on an internal lock</b>, including step().
   This allows step() to synchronize on the lock only to suck out the relevant Steppables from the Heap and to advance the timestep;
   all other portions of step() are outside of the lock.  Thus when step() actually steps the Steppables, even in different threads
   (like AsynchronousSteppable or ParallelSequence), they can turn around and submit step-requests to the Schedule even while it's still
   in its step() method.
   
   <p>One downside to this flexibility is that it's very inefficient to check, at each step of a Steppable, whether the Schedule
   has been reset or not.  Thus now if you call reset() or [better] SimState.kill(), the Schedule will continue to step Steppables
   until it has exhausted ones scheduled for the current timestep.  Only at that point will it cease.
   
   <p><b>Heaps and Calendar Queues</b>.  Schedule uses a plain-old binary heap for its queueing mechanism.  This is reasonably efficient,
   but it could be made more efficient with a Calendar Queue designed for the purposes of your simulation.  We settled on a Heap because
   we do not know what the expected scheduling pattern will be for any given simulation, and so had to go for the most general case.  If you'd
   care to customize your queue, you can do so by overriding the createHeap() method in a custom Schedule.  We imagine this would be rare.
*/
    

public class Schedule implements java.io.Serializable
    {
    private static final long serialVersionUID = 1;

    /** 首个可合法调度事件的时间（0.0），仿真的 “时间起点” */
    public static final double EPOCH = 0.0;
    /** 表示仿真尚未开始（值为 EPOCH - 1 = -1.0） */
    public static final double BEFORE_SIMULATION = EPOCH - 1.0;
    /** 表示仿真已结束（值为正无穷 Double.POSITIVE_INFINITY）*/
    public static final double AFTER_SIMULATION = Double.POSITIVE_INFINITY;
    /** 比 EPOCH 大的最小时间单位（解决 “同一时间调度” 的精度问题） */
    public static final double EPOCH_PLUS_EPSILON = Double.longBitsToDouble(Double.doubleToRawLongBits(EPOCH)+1L);
    /** 因浮点数精度丢失，无法再精确区分整数步长的临界值（≈9e15）。  也就是说, MAXIMUM_INTEGER + 1.0 == MAXIMUM_INTEGER. */
    public static final double MAXIMUM_INTEGER = 9.007199254740992E15;

    // 是否应当对具有相同时间步长和排序序号的个体进行打乱处理？
    boolean shuffling = true;  // 默认情况下，我们希望进行打乱（洗牌）操作。

    /** 调度程序队列。 */
    protected Heap queue = createHeap();
    
    /** 返回供调度器（Schedule）使用的堆结构。默认情况下，返回一个二叉堆。你可以重写此方法，以提供针对特定问题优化的、自定义的堆子类。 */
    protected Heap createHeap() { return new Heap(); }
    
    /**通过 getTime() 方法返回的当前时间。
     若在子类中修改该值，请务必先基于 Schedule.lock 进行同步处理。*/
    protected double time;
    
    /** 通过 getSteps() 方法返回的当前步数。
     若在子类中修改该值，请务必先基于 Schedule.lock 进行同步处理。*/
    protected long steps;
        
    /** 通过 isSealed() 方法返回的调度器是否已被锁定。
     若在子类中修改该值，请务必先基于 Schedule.lock 进行同步处理。 */
    protected boolean sealed = false;
                
    /** 调度器锁。许多方法在修改内部变量前，都会基于该锁进行同步操作。 */
    protected Object lock = new boolean[1];  // 数组是一种唯一的、可序列化的对象。
    
    /** 设置调度器在满足以下条件时，是否随机打乱可执行单元（Steppables）的执行顺序（默认开启打乱）：
     当这些可执行单元拥有相同的排序序号，且被调度在同一时间点执行时触发该规则。
     如果关闭随机打乱，那么排序序号相同、调度时间相同的可执行单元，将按照它们被添加到调度器中的原始顺序依次执行。
     仅在你明确知晓操作目的的特殊场景下，才需要将该值设为 FALSE；绝大多数场景下，建议保持默认值 TRUE（开启随机打乱）。 */
    private void setShuffling(boolean val)
        {
        synchronized(lock)
            {
            shuffling = val;
            }
        }
        
    /** 如果多个可执行单元（Steppables）拥有相同的排序序号且被调度在同一时间执行时，
        其执行顺序会被随机打乱，则返回 true（默认值）；否则返回 false，
        表示拥有相同排序序号的可执行单元将按照它们被添加到调度器中的顺序执行。 */
    private boolean isShuffling()
        {
        synchronized(lock)
            {
            return shuffling;
            }
        }
        
    /** Schedule 构造函数*/
    public Schedule()
        {
        time = BEFORE_SIMULATION;
        steps = 0;
        }
    
    /** 返回当前时间步，不安全、快、内部用
        @Deprecated use getTime()
    */
    public double time() { return getTime(); }

    /** 返回当前时间步。安全、规范、你用 */
    public double getTime() { synchronized(lock) { return time; } }
    
    /** 返回调度器是否已被锁定（即使调度尚未到达 AFTER_SIMULATION 状态，也无法再添加任何调度任务）。
     调用 reset() 方法可解锁调度器，调用 seal() 方法可锁定调度器。  */
    public boolean isSealed() { synchronized(lock) { return sealed; } }
        
    /** 以字符串格式返回当前时间。
     如果时间为 BEFORE_SIMULATION（仿真开始前），则返回 beforeSimulationString；
     如果时间为 AFTER_SIMULATION（仿真结束后），则返回 afterSimulationString；
     其他情况下，返回时间的数值形式字符串。 */
    public String getTimestamp(final String beforeSimulationString, final String afterSimulationString)
        {
        return getTimestamp(getTime(), beforeSimulationString, afterSimulationString);
        }
    
    /** 以字符串格式返回指定时间。
     如果该时间早于EPOCH（例如仿真开始前 BEFORE_SIMULATION），则返回 beforeSimulationString；
     如果该时间为仿真结束后 AFTER_SIMULATION，则返回 afterSimulationString；
     其他情况下，返回时间的数值形式字符串。 */
    // 可以设为静态（static），但为何不允许它被重写（override）呢？
    public String getTimestamp(double time, final String beforeSimulationString, final String afterSimulationString)
        {
        if (time < EPOCH) return beforeSimulationString;
        if (time >= AFTER_SIMULATION) return afterSimulationString;
        if (time == (long)time) return Long.toString((long)time);
        return Double.toString(time);
        }

    /** 返回调度器截至目前已触发的步数。 */
    public long getSteps() { synchronized(lock) { return steps; } }

        // 将时间切换至仿真结束状态（AFTER_SIMULATION），并尝试终止所有剩余的调度项
        // @deprecated 已废弃，请勿使用
    private void pushToAfterSimulation()
        {
        synchronized(lock)
            {
            time = AFTER_SIMULATION;
            queue = createHeap();  // let 'em GC  -- must be inside the lock so scheduleOnce doesn't try to add more
            }
        }

    /** 清空调度器，但不重置时间或步数。
     如果你想在可执行单元（Steppable）中终止仿真，请改用 SimState.kill() 方法。
     注意：当前时间步中已处于待执行状态的所有代理（agents）仍会被执行—— 包括它们可能会再次将自身重新加入调度器。  */
    public void clear()
        {
        synchronized(lock)
            {
            queue = createHeap();  // let 'em GC  -- must be inside the lock so scheduleOnce doesn't try to add more
            }
        }

    /** 锁定调度器：调度器一旦被锁定后，无法再向其添加任何可执行单元（Steppables）。
     必须调用 reset() 方法才能解锁调度器。
     如果你想在可执行单元中终止仿真，请改用 SimState.kill() 方法。  */
    public void seal()
        {
        synchronized(lock)
            {
            sealed = true;
            }
        }

    /** 清空调度器，并将其重置为初始状态（仿真开始前 BEFORE_SIMULATION），步数归零（steps = 0）。
     如果你希望在可执行单元中终止仿真，请改用 SimState.kill() 方法。 */
    public void reset()
        {
        synchronized(lock)
            {
            time = BEFORE_SIMULATION;
            steps = 0;
            queue = createHeap();  // let 'em GC  -- must be inside the lock so scheduleOnce doesn't try to add more
            sealed = false;
            }
        }
    
    /** 如果调度器中没有任何待执行任务，则返回 true。*/
    public boolean scheduleComplete()
        {
        synchronized(lock)
            {
            return queue.isEmpty();
            }
        }
    
    /**
     将指定调度器合并到当前调度器中。
     源调度器不会被修改，但当前调度器的队列会被更改。
     注意：该方法非线程安全，仅应在没有其他线程可能操作该调度器时执行
    */
    public void merge(Schedule other) 
        {
        if (inStep || other.inStep) 
            throw new RuntimeException("May not merge with another schedule while inside a step method.");
        if (sealed || other.sealed)
            throw new RuntimeException("May not merge with a sealed schedule.");
        if (!other.queue.isEmpty())
            {
            double minKey = ((Key)(other.queue.getMinKey())).getTime();
            if (minKey <= getTime())  // uh oh
                throw new RuntimeException("May not merge with a schedule which has Steppables scheduled for an earlier time than my current time value."); 
            }
        
        queue = queue.merge(other.queue);
        }
        
    /** 在 SimState.finish() 中被调用，用于清空 beforeSteps 和 afterSteps 集合。  */
    void clearBeforeAndAfter()
        {
        beforeSteps = new Bag();
        afterSteps = new Bag();
        }
        
    /** Adds a steppable to be called every iteration of the Schedule immediately before any other Steppables
        are called for that step.  You should do this only in your SimState.start() method (super.start() clears
        out all current steppables of this type).  If multiple items are added with this method,
        no guarantee is made on the order in which the Schedule will call them. */
    public void addBefore(Steppable step)
        {
        beforeSteps.add(step);
        }
    
    /** Adds a steppable to be called every iteration of the Schedule immediately after any other Steppables
        are called for that step.  You should do this only in your SimState.start() method (super.start() clears
        out all current steppables of this type).  If multiple items are added with this method,
        no guarantee is made on the order in which the Schedule will call them. */
    public void addAfter(Steppable step)
        {
        afterSteps.add(step);
        }
        
    Bag beforeSteps = new Bag();
    Bag afterSteps = new Bag();

    //当前这一步要执行的所有智能体、事件
    Bag currentSteps = new Bag();
    //创建一个临时容器，用于批量提取同一时间、同一优先级的仿真事件
    Bag substeps = new Bag();
    boolean inStep = false;  // 防止重入
    /** 执行调度器步进：收集并排序下一个时间步需要执行的所有任务（自动跳过空白时间步），然后按照确定的顺序执行所有任务。
     如果未执行任何任务（调度器已无剩余任务或时间已耗尽），则返回 FALSE。 */
    public synchronized boolean step(final SimState state)
        {

        if (inStep)  // 检测并拒绝重入调用
            {
            throw new RuntimeException("Schedule.step() is not reentrant, yet is being called recursively.");
            }
        inStep = true;


            //当前这一步要执行的所有智能体、事件
        Bag currentSteps = this.currentSteps;  // 使用局部变量速度更快
            //state 中传入的随机数生成器
        final MersenneTwisterFast random = state.random; // 使用局部变量速度更快
        
        int topSubstep = 0;  // 我们把这个设置作为一种临时变通方案（hack），避免每次都清空所有子步骤，直到最后才统一清空。

        // 尽快获取所有事件
        synchronized(lock)
            {
            if (time == AFTER_SIMULATION || queue.isEmpty())  //queue.isEmpty()检查queue是否为空
                { time = AFTER_SIMULATION; inStep = false; return false; }  // 推进时间，专门处理队列判空（queue.isEmpty ()）的逻辑
            
            // 现在修改时间
            time = ((Key)(queue.getMinKey())).time;  // 键值不应为空；时间值应始终比原数值大 1。

            final boolean shuffling = this.shuffling; // 局部变量速度更快。此变量需要在锁内部进行同步操作。

            //将substeps Bag 中数据转到 currentSteps Bag 中（读取进度操作）
            // 按正确顺序获取所有可步进执行对象。为此，我们使用两个集合（Bag）：
            // 1. while 循环的每次迭代中，获取下一个排序层级的所有可步进执行对象，放入子步骤集合（substeps Bag）
            // 2. 接着对子步骤集合进行乱序或倒序处理
            // 3. 然后将这些对象全部添加到当前步骤集合（currentSteps Bag）的末尾
            // 4. 清空子步骤集合，但暂不允许它们被垃圾回收（GC）
            // 5. 最后，退出 while 循环后，"真正"清空子步骤集合，允许它们被垃圾回收
            while(true)
                {
                // 提取同时间、同优先级的所有事件 → 放进 substeps
                queue.extractMin(substeps);  // 以相反的顺序输出， queue.extractMin()是提取堆中的最小元素的方法

                // 洗牌
                if (substeps.numObjs > 1)  //substeps.numObjs 当前 substeps 集合里实际存储的元素数量（也就是待执行的事件个数）
                    {
                    if (shuffling) substeps.shuffle(random);  // 无需反转顺序——我们正在进行随机打乱
                    else substeps.reverse();  // 它们是以逆序取出的；我们需要将其反转
                    }
                                                                
                // 全部输出
                if (topSubstep < substeps.numObjs) topSubstep = substeps.numObjs;  // 记住最大子步骤的索引，因为我们没有执行常规的清空操作
                currentSteps.addAll(substeps);  //  将当前时间、当前优先级的一批待执行事件，合并到总执行列表中，为后续统一执行做准备。
                substeps.numObjs = 0;  // 临时清空
                
                // 检查下一个键值，如果无需继续则跳出循环
                Key currentKey = (Key)(queue.getMinKey()); //  currentKey.time = 下一个事件的执行时间
                if (currentKey == null || currentKey.time != time) break;  // 看起来在当前时间戳下，已经没有更多子步骤了
                }
            }

        // 现在终于真正清空子步骤集合了
        substeps.numObjs = topSubstep;  //  结束时 topSubstep = 0
        substeps.clear();  // 彻底清空，以便所有对象都能被垃圾回收


        // 执行
        int len = currentSteps.numObjs;  //  需要运行的步骤数
        Object[] objs = currentSteps.objs;  //  直接获取 currentSteps 这个集合内部真正存数据的数组

        int sz = beforeSteps.size();  //  前置任务的个数
        //  执行前置任务
        for(int x = 0; x < sz; x++)
            {
            ((Steppable)(beforeSteps.get(x))).step(state);
            }
                
        try
            {
            for(int x=0;x<len;x++)  // 执行完前置任务仍未终止
                {
                assert sim.util.LocationLog.set(((Steppable)(objs[x])));  //  记录日志，标记当前正在执行的仿真对象

                ((Steppable)(objs[x])).step(state);  //  真正执行仿真对象的逻辑（调用继承 Steppable 的类的 step 方法）

                assert sim.util.LocationLog.clear();  //  清空日志
                objs[x] = null;  // 即便正在被终止，也要允许垃圾回收
                }

            }
        finally
            {
            // 复用 currentSteps 集合 —— 所有对象应当已经被释放并交由垃圾回收（GC）处理，无需调用 clear () 方法。
            currentSteps.numObjs = 0;
                
            synchronized(lock) { steps++; }
            inStep = false;
            }

        //后置任务
        sz = afterSteps.size();
        for(int x = 0; x < sz; x++)
            {
            ((Steppable)(afterSteps.get(x))).step(state);
            }
                
        return true;
        }

        /**
          将事件调度至 getTime() + 1.0 时刻执行，排序等级为 0。
          若时间与事件均合法，则调度该事件并返回 true。

          当下述情况发生时，该方法返回 false：
          调度器无法再调度更多事件（已被锁定 或 时间为仿真结束后），
          或事件被指定在仿真结束后执行。

          若事件调度时间不合法 或 事件为 null，
          该方法将抛出 IllegalArgumentException 异常。*/
    
    // // 加同步锁，保证 “取当前时间” 和 “调度事件” 这两步绑定在一起执行，不会被其他线程打断，避免时间错乱。
    public boolean scheduleOnce(final Steppable event)
        {
        synchronized(lock)
            {
            return _scheduleOnce(new Key(/*must lock for:*/time +1.0,0),event);
            }
        }
    
    /** Schedules the event to occur at getTime() + delta, 0 ordering. If this is a valid time
        and event, schedules the event and returns TRUE.  If the delta = 0, 
        then the first event is instead scheduled to occur at getTime() + delta + epsilon (the minimum possible next
        timestamp).
        This method at present returns FALSE if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION), or if the
        event is being scheduled for AFTER_SIMULATION.  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. */
    
    // synchronized so getting the time can be atomic with the subsidiary scheduleOnce function call
    public boolean scheduleOnceIn(final double delta, final Steppable event)
        {
        synchronized(lock)
            {
            return _scheduleOnce(new Key(/*must lock for:*/ time + delta, 0), event);
            }
        }
        
    /** Schedules the event to occur at getTime() + 1.0, and in the ordering provided. If this is a valid time
        and event, schedules the event and returns TRUE.
        This method at present returns FALSE if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION), or if the
        event is being scheduled for AFTER_SIMULATION.  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. */
    
    // synchronized so getting the time can be atomic with the subsidiary scheduleOnce function call
    public boolean scheduleOnce(final Steppable event, final int ordering)
        {
        synchronized(lock)
            {
            return _scheduleOnce(new Key(/*must lock for:*/time +1.0,ordering),event);
            }
        }

    /** Schedules the event to occur at getTime() + delta, and in the ordering provided. If this is a valid time
        and event, schedules the event and returns TRUE.  If the delta = 0, 
        then the first event is instead scheduled to occur
        This method at present returns FALSE if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION), or if the
        event is being scheduled for AFTER_SIMULATION.  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. */
    
    // synchronized so getting the time can be atomic with the subsidiary scheduleOnce function call
    public boolean scheduleOnceIn(final double delta, final Steppable event, final int ordering)
        {
        synchronized(lock)
            {
            return _scheduleOnce(new Key(/*must lock for:*/ time + delta, ordering), event);
            }
        }

    /** Schedules the event to occur at the provided time, 0 ordering.  If the getTime() == the provided
        time, then the event is instead scheduled to occur at getTime() + epsilon (the minimum possible next
        timestamp). If this is a valid time
        and event, schedules the event and returns TRUE.
        This method at present returns FALSE if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION), or if the
        event is being scheduled for AFTER_SIMULATION.  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. */
    
    public boolean scheduleOnce(double time, final Steppable event)
        {
        synchronized(lock)
            {
            return _scheduleOnce(new Key(time,0),event);
            }
        }
        
    /** Schedules the event to occur at the provided time, and in the ordering provided.  If the getTime() == the provided
        time, then the event is instead scheduled to occur at getTime() + epsilon (the minimum possible next
        timestamp). If this is a valid time, ordering,
        and event, schedules the event and returns TRUE.
        This method at present returns FALSE if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION), or if the
        event is being scheduled for AFTER_SIMULATION.  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. 
    */
    public boolean scheduleOnce(double time, final int ordering, final Steppable event)
        {
        synchronized(lock)
            {
            return _scheduleOnce(new Key(time,ordering),event);
            }
        }
    
    /** Schedules an item. 
        This method at present returns FALSE if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION), or if the
        event is being scheduled for AFTER_SIMULATION.  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. */
    boolean scheduleOnce(Key key, final Steppable event)
        {
        synchronized(lock)
            {
            return _scheduleOnce(key, event);
            }
        }

    
    /** Schedules an item.  You must synchronize on this.lock before calling this method.   This allows us to avoid synchronizing twice,
        and incurring any overhead (not sure if that's an issue really).  This method at present returns FALSE if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION), or if the
        event is being scheduled for AFTER_SIMULATION.  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. */
    protected boolean _scheduleOnce(Key key, Steppable event)
        {
        // locals are a teeny bit faster
        double time = this.time;
        double t = key.time;

        // check to see if we're scheduling for the same exact time -- even if of different orderings, that doesn't matter
        if (t == time && t != AFTER_SIMULATION)
            // bump up time to the next possible item, unless we're at infinity already (AFTER_SIMULATION)
            {
            key.time = Math.nextUp(key.time);  //Double.longBitsToDouble(Double.doubleToRawLongBits(t)+1L);
            t = key.time; 
            }

        if (sealed || t >= AFTER_SIMULATION)             // situations where no further events can be added
            {
            return false;
            }
        else if (t < EPOCH)
            throw new IllegalArgumentException("For the Steppable...\n\n"+event+
                "\n\n...the time provided ("+t+") is < EPOCH (" + EPOCH + ")");
        else if (t != t /* NaN */)
            throw new IllegalArgumentException("For the Steppable...\n\n"+event+
                "\n\n...the time provided ("+t+") is NaN");
        else if (t < time)
            throw new IllegalArgumentException("For the Steppable...\n\n"+event+
                "\n\n...the time provided ("+t+") is less than the current time (" + time + ")");
        else if (event == null)
            throw new IllegalArgumentException("The provided Steppable is null");
        
        queue.add(event, key);
        
        return true;
        }


    /** Schedules the event to recur at an interval of 1.0 starting at getTime() + 1.0, and at 0 ordering.
        If this is a valid event, schedules the event and returns a Stoppable, else returns null.
        The recurrence will continue until getTime() >= AFTER_SIMULATION, the Schedule is cleared out,
        or the Stoppable's stop() method is called, whichever happens first.

        <p>This method at present returns null if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION).  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. 

        <p> Note that calling stop() on the Stoppable
        will not only stop the repeating, but will <i>also</i> make the Schedule completely
        forget (lose the pointer to) the Steppable scheduled here.  This is particularly useful
        if you need to make the Schedule NOT serialize certain Steppable objects. */
    
    // synchronized so getting the time can be atomic with the subsidiary scheduleRepeating function call
    public IterativeRepeat scheduleRepeating(final Steppable event)
        {
        synchronized(lock)
            {
            return scheduleRepeating(/*must lock for:*/time +1.0,0,event,1.0);
            }
        }

    /** Schedules the event to recur at the specified interval starting at getTime() + interval, and at 0 ordering.
        If this is a valid interval (must be > 0)
        and event, schedules the event and returns a Stoppable, else returns null.
        The recurrence will continue until getTime() >= AFTER_SIMULATION, the Schedule is cleared out,
        or the Stoppable's stop() method is called, whichever happens first.
        
        <p><b>WARNING:</b>Use of this method in start() might lead to confusion.  At start(), the timestep is -1
        because the first actual timestep in the simulation is 0.  This means that if you schedule an agent
        with this method for interval 10 (say), it won't start at 0 but rather will start at <b>9</b>.  
        Instead you might want to use <tt>scheduleRepeating(0, event, interval).</tt>

        <p>This method at present returns null if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION).  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. 

        <p> Note that calling stop() on the Stoppable
        will not only stop the repeating, but will <i>also</i> make the Schedule completely
        forget (lose the pointer to) the Steppable scheduled here.  This is particularly useful
        if you need to make the Schedule NOT serialize certain Steppable objects. */
    
    // synchronized so getting the time can be atomic with the subsidiary scheduleRepeating function call
    public IterativeRepeat scheduleRepeating(final Steppable event, final double interval)
        {
        synchronized(lock)
            {
            return scheduleRepeating(/*must lock for:*/time +interval,0,event,interval);
            }
        }

    /** Schedules the event to recur at the specified interval starting at getTime() + interval, and at the provided ordering.
        If this is a valid interval (must be > 0)
        and event, schedules the event and returns a Stoppable, else returns null.
        The recurrence will continue until getTime() >= AFTER_SIMULATION, the Schedule is cleared out,
        or the Stoppable's stop() method is called, whichever happens first.

        <p><b>WARNING:</b>Use of this method in start() might lead to confusion.  At start(), the timestep is -1
        because the first actual timestep in the simulation is 0.  This means that if you schedule an agent
        with this method for interval 10 (say), it won't start at 0 but rather will start at <b>9</b>.  
        Instead you might want to use <tt>scheduleRepeating(0, event, ordering, interval).</tt>

        <p>This method at present returns null if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION).  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. 

        <p> Note that calling stop() on the Stoppable
        will not only stop the repeating, but will <i>also</i> make the Schedule completely
        forget (lose the pointer to) the Steppable scheduled here.  This is particularly useful
        if you need to make the Schedule NOT serialize certain Steppable objects. */
    
    // synchronized so getting the time can be atomic with the subsidiary scheduleRepeating function call
    public IterativeRepeat scheduleRepeating(final Steppable event, final int ordering, final double interval)
        {
        synchronized(lock)
            {
            return scheduleRepeating(/*must lock for:*/time +interval,ordering,event,interval);
            }
        }

    /** Schedules the event to recur at the specified interval starting at the provided time, and at 0 ordering.
        If the getTime() == the provided
        time, then the first event is instead scheduled to occur at getTime() + epsilon (the minimum possible next
        timestamp). If this is a valid time, ordering, interval (must be positive), 
        and event, schedules the event and returns a Stoppable, else returns null.
        The recurrence will continue until getTime() >= AFTER_SIMULATION, the Schedule is cleared out,
        or the Stoppable's stop() method is called, whichever happens first.
    
        <p>This method at present returns null if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION).  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. 

        <p> Note that calling stop() on the Stoppable
        will not only stop the repeating, but will <i>also</i> make the Schedule completely
        forget (lose the pointer to) the Steppable scheduled here.  This is particularly useful
        if you need to make the Schedule NOT serialize certain Steppable objects. */

    public IterativeRepeat scheduleRepeating(final double time, final Steppable event)
        {
        // No need to lock -- we're not grabbing time from the schedule
        return scheduleRepeating(time,0,event,1.0);
        }

    /** Schedules the event to recur at the specified interval starting at the provided time, 
        in ordering 0.  If the getTime() == the provided
        time, then the first event is instead scheduled to occur at getTime() + epsilon (the minimum possible next
        timestamp). If this is a valid time, interval (must be > 0), 
        and event, schedules the event and returns a Stoppable, else returns null.
        The recurrence will continue until getTime() >= AFTER_SIMULATION, the Schedule is cleared out,
        or the Stoppable's stop() method is called, whichever happens first.
    
        <p>This method at present returns null if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION).  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. 

        <p> Note that calling stop() on the Stoppable
        will not only stop the repeating, but will <i>also</i> make the Schedule completely
        forget (lose the pointer to) the Steppable scheduled here.  This is particularly useful
        if you need to make the Schedule NOT serialize certain Steppable objects. */

    public IterativeRepeat scheduleRepeating(final double time, final Steppable event, final double interval)
        {
        // No need to lock -- we're not grabbing time from the schedule
        return scheduleRepeating(time,0,event,interval);
        }

    /** Schedules the event to recur at an interval of 1.0 starting at the provided time, 
        and in the ordering provided.  If the getTime() == the provided
        time, then the first event is instead scheduled to occur at getTime() + epsilon (the minimum possible next
        timestamp). If this is a valid time, ordering,
        and event, schedules the event and returns a Stoppable, else returns null.
        The recurrence will continue until getTime() >= AFTER_SIMULATION, the Schedule is cleared out,
        or the Stoppable's stop() method is called, whichever happens first.
    
        <p>This method at present returns null if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION).  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. 

        <p> Note that calling stop() on the Stoppable
        will not only stop the repeating, but will <i>also</i> make the Schedule completely
        forget (lose the pointer to) the Steppable scheduled here.  This is particularly useful
        if you need to make the Schedule NOT serialize certain Steppable objects. */

    public IterativeRepeat scheduleRepeating(final double time, final int ordering, final Steppable event)
        {
        // No need to lock -- we're not grabbing time from the schedule
        return scheduleRepeating(time,ordering,event,1.0);
        }

    /** Schedules the event to recur at the specified interval starting at the provided time, 
        and in the ordering provided.  If the getTime() == the provided
        time, then the first event is instead scheduled to occur at getTime() + epsilon (the minimum possible next
        timestamp). If this is a valid time, ordering, interval (must be > 0), 
        and event, schedules the event and returns a Stoppable.
        The recurrence will continue until getTime() >= AFTER_SIMULATION, the Schedule is cleared out,
        or the Stoppable's stop() method is called, whichever happens first.
                
        <p>This method at present returns null if the schedule cannot
        schedule any more events (it's sealed or the time is AFTER_SIMULATION).  The method 
        throws an IllegalArgumentException if the event is being scheduled for an invalid time, or is null. 
    
        <p> Note that calling stop() on the Stoppable
        will not only stop the repeating, but will <i>also</i> make the Schedule completely 
        forget (lose the pointer to) the Steppable scheduled here.  This is particularly useful
        if you need to make the Schedule NOT serialize certain Steppable objects. */

    public IterativeRepeat scheduleRepeating(final double time, final int ordering, final Steppable event, final double interval)
        {
        if (interval <= 0) throw new IllegalArgumentException("The steppable " +  event + " was scheduled repeating with an impossible interval ("+interval+")");
        IterativeRepeat r = new IterativeRepeat(event, time, interval, ordering);

        synchronized(lock)
            {
            if (_scheduleOnce(r.getKey(),r)) return r;
            else return null;
            }
        }

    /** Timestamps stored as keys in the heap.  Comps are comparable by their time first, and their ordering second. */
    protected static class Key implements Comparable, Serializable
        {
        double time;
        int ordering;
                
        public int getOrdering() { return ordering; }
        public double getTime() { return time; }
            
        public Key(double time, int ordering)
            {
            this.time = time;
            this.ordering = ordering;
            }
                
        public boolean equals(Object obj)
            {
            if (obj != null && obj instanceof Key)
                {
                Key o = (Key)obj;
                return (o.time == time && o.ordering == ordering);
                }
            else return false;
            }
                        
        public int hashCode()
            {
            int y = ordering;
            y += ~(y << 15);
            y ^=  (y >>> 10);
            y +=  (y << 3);
            y ^=  (y >>> 6);
            y += ~(y << 11);
            y ^=  (y >>> 16);

            long key = Double.doubleToRawLongBits(time);  // we can't ever be NaN or infinity, so this is okay
            key += ~(key << 32);
            key ^= (key >>> 22);
            key += ~(key << 13);
            key ^= (key >>> 8);
            key += (key << 3);
            key ^= (key >>> 15);
            key += ~(key << 27);
            key ^= (key >>> 31);

            return (int)(key ^ (key >> 32)) ^ y;
            }
                    
        public int compareTo(Object obj)
            {
            Key o = (Key)obj;
            double time = this.time;
            double time2 = o.time;
            if (time == time2)  // the most common situation
                {
                int ordering = this.ordering;
                int ordering2 = o.ordering;
                if (ordering == ordering2) return 0;  // the most common situation
                if (ordering < ordering2) return -1;
                /* if (ordering > ordering2) */ return 1;
                }
            // okay, so they're different times
            if (time < time2) return -1;
            /* if (time > time2) */ return 1;
            }
        }
    }
