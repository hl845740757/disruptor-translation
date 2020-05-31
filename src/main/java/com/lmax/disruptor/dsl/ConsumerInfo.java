package com.lmax.disruptor.dsl;

import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;

import java.util.concurrent.Executor;

/**
 * 一个消费者信息的抽象，
 */
interface ConsumerInfo
{
    /**
     * 获取消费者拥有的所有的序列，一个消费者可能有多个Sequence，消费者的消费进度由最小的Sequence决定。
     * 这里返回数组类型，其实仅仅是为了避免{@link com.lmax.disruptor.FixedSequenceGroup}。
     */
    Sequence[] getSequences();

    /**
     * 获取当前消费者持有的序列屏障。
     * 其作用详见{@link SequenceBarrier}
     *
     * {@link com.lmax.disruptor.Sequencer#newBarrier(Sequence...)}
     */
    SequenceBarrier getBarrier();

    /**
     * 当前消费者是否是消费链末端的消费者(没有后继消费者)。
     * 如果是末端的消费者，那么它就是生产者关注的消费者（网关序列）。
     *
     * {@link com.lmax.disruptor.Sequencer#addGatingSequences(Sequence...)}
     * {@link com.lmax.disruptor.Sequencer#removeGatingSequence(Sequence)}}
     * {@link com.lmax.disruptor.AbstractSequencer#gatingSequences}
     */
    boolean isEndOfChain();

    /**
     * 启动消费者。
     * 主要是为每一个{@link com.lmax.disruptor.EventProcessor}创建线程，启动事件监听。
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
     * 生产者就不再需要对我保持关注
     */
    void markAsUsedInBarrier();

    /**
     * 消费者当前是否正在运行
     */
    boolean isRunning();
}
