package com.lmax.disruptor;

/**
 * 事件序号生成器
 * @param <T>
 */
public interface EventSequencer<T> extends DataProvider<T>, Sequenced
{

}
