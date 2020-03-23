package com.lmax.disruptor.dsl;

import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;

import java.util.concurrent.Executor;

/**
 * 消费者信息
 */
interface ConsumerInfo
{
    /**
     * 获取消费者拥有的所有的序列，消费者的消费进度由最小的Sequence决定
     * 一个消费者可能有多个Sequence，在某些时候可以保持简单性，对多线程的消费者有重要意义(WorkerPool中)。
     * @see com.lmax.disruptor.WorkerPool
     *
     * 消费者之间的可见性保证：
     * 我的所有直接前驱消费者与当前消费者之间的可见性保证。
     * Sequence是单调递增的，当看见前驱消费者的进度增大时，所有前驱消费者对区间段内的数据的处理对当前消费者来说都是可见的。
     * volatile的happens-before原则-----前驱消费者们的进度变大(写volatile)先于我看见它变大(读volatile)。
     *
     * 注意：相同的可见性策略---与Sequence之间交互的消费者之间的可见性保证。
     * {@link com.lmax.disruptor.AbstractSequencer#gatingSequences}
     *
     * 且两个可见性保证具备传递性。 生产者 先于 前驱消费者， 前驱消费者 先于 当前消费者，当前消费者 先于 我的后继消费者
     *
     * @return
     */
    Sequence[] getSequences();

    /**
     * 获取当前消费者持有的序列屏障
     * 其作用详见{@link SequenceBarrier}
     *
     * {@link com.lmax.disruptor.Sequencer#newBarrier(Sequence...)}
     * @return
     */
    SequenceBarrier getBarrier();

    /**
     * 当前消费者是否是消费链末端的消费者(没有后继消费者)
     * 如果是末端的消费者，那么它就是生产者关注的消费者对象
     *
     * {@link com.lmax.disruptor.Sequencer#addGatingSequences(Sequence...)}
     * {@link com.lmax.disruptor.Sequencer#removeGatingSequence(Sequence)}}
     * {@link com.lmax.disruptor.AbstractSequencer#gatingSequences}
     * @return
     */
    boolean isEndOfChain();

    /**
     * 启动消费者。
     * 主要是为每一个{@link com.lmax.disruptor.EventProcessor}创建线程,启动事件监听
     * @param executor
     */
    void start(Executor executor);

    /**
     * 通知消费者处理完当前事件之后，停止下来
     * 类似线程中断或任务的取消操作
     * {@link java.util.concurrent.Future#cancel(boolean)}
     * {@link Thread#interrupt()}
     */
    void halt();

    /**
     * 当我新增了后继消费者的时候，标记为不是消费者链末端的消费者
     * 生产者就不在需要对我保持关注
     */
    void markAsUsedInBarrier();

    /**
     * 消费者当前是否正在运行
     */
    boolean isRunning();
}
