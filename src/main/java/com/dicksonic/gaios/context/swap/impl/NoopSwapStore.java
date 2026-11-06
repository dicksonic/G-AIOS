package com.dicksonic.gaios.context.swap.impl;

import com.dicksonic.gaios.context.swap.SwapStore;
import com.dicksonic.gaios.context.swap.event.SwapEvent;

/**
 * 空实现：不落盘，行为与 D6 完全一致。
 * Context 无参构造默认装配它——不接 D7 的既有代码零回归。
 */
public class NoopSwapStore implements SwapStore {

    @Override
    public void spill(SwapEvent event) {
        // 故意为空：纯透传，按约定不打日志保持信噪比
    }
}