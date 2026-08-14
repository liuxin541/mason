/*
  Copyright 2006 by Sean Luke and George Mason University
  Licensed under the Academic Free License version 3.0
  See the file "LICENSE" for more information
*/

package sim.engine;

import java.lang.reflect.Constructor;

/** 这是一个工厂接口，专门用来生成 SimState 对象，仅供框架内部调用，不对外开放给用户使用。
 一个用于可创建 SimState 子类的类所实现的接口。
 通常你不会直接使用这个接口；它主要供 SimState.doLoop 方法内部使用。 */
        
public interface MakesSimState
    {
    /** 使用指定的随机数种子以及传入 main (...) 方法的命令行参数，创建一个 SimState 子类实例。*/
    public SimState newInstance(long seed, String[] args);
        
    /** 返回将要生成的 SimState 子类 的类型（Class 对象）。*/
    public Class simulationClass();

    /** 返回将要生成的 SimState 子类 的构造方法。*/
    public Constructor[] getConstructors();
    }
