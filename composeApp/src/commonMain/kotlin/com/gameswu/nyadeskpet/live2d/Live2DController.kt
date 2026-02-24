package com.gameswu.nyadeskpet.live2d

import com.gameswu.nyadeskpet.agent.Live2DCommandData
import com.gameswu.nyadeskpet.agent.SyncCommandData
import com.gameswu.nyadeskpet.agent.ParameterSet as AgentParameterSet
import com.gameswu.nyadeskpet.currentTimeMillis
import kotlinx.coroutines.*
import kotlin.math.max

/**
 * Controller for Live2D models, handling higher-level animation logic.
 */
class Live2DController(private val manager: Live2DManager) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Handles a single Live2D command.
     */
    fun handleCommand(cmd: Live2DCommandData) {
        when (cmd.command) {
            "motion" -> manager.playMotion(
                group = cmd.group ?: "",
                index = cmd.index ?: 0,
                priority = cmd.priority ?: 2
            )
            "expression" -> manager.setExpression(cmd.expressionId ?: "")
            "parameter" -> {
                cmd.parameters?.let { params ->
                    params.forEach { p -> animateParameter(p.toLocalParameterSet()) }
                } ?: run {
                    cmd.parameterId?.let { id ->
                        cmd.value?.let { v ->
                            manager.setParameterValue(id, v, cmd.weight ?: 1f)
                        }
                    }
                }
            }
        }
    }

    /**
     * Executes a sync command containing multiple actions.
     * Actions are executed sequentially; if `waitComplete` is set,
     * subsequent actions wait for the current one to complete.
     */
    fun executeSyncCommand(data: SyncCommandData) {
        scope.launch {
            for (action in data.actions) {
                when (action.type) {
                    "motion" -> {
                        manager.playMotion(
                            group = action.group ?: "",
                            index = action.index ?: 0,
                            priority = 2
                        )
                        if (action.waitComplete == true) {
                            delay(action.duration ?: 2000L)
                        }
                    }
                    "expression" -> {
                        action.expressionId?.let { manager.setExpression(it) }
                    }
                    "parameter" -> {
                        action.parameters?.forEach { param ->
                            animateParameter(param.toLocalParameterSet())
                        } ?: run {
                            action.parameterId?.let { id ->
                                action.value?.let { v ->
                                    manager.setParameterValue(id, v, action.weight ?: 1f)
                                }
                            }
                        }
                        if (action.waitComplete == true) {
                            delay(action.duration ?: 1000L)
                        }
                    }
                    "dialogue" -> {
                        // 对话动作由上层 DialogueManager 处理
                        if (action.waitComplete == true) {
                            delay(action.duration ?: 3000L)
                        }
                    }
                }
            }
        }
    }

    /**
     * Animates a parameter through its transition stages.
     */
    fun animateParameter(param: ParameterSet) {
        scope.launch {
            // 1. Transition In
            val startTime = currentTimeMillis()
            while (currentTimeMillis() - startTime < param.transitionInMs) {
                val progress = (currentTimeMillis() - startTime).toFloat() / param.transitionInMs
                manager.setParameterValue(param.id, progress * param.value, param.weight)
                delay(16)
            }

            // 2. Hold
            manager.setParameterValue(param.id, param.value, param.weight)
            delay(param.holdMs)

            // 3. Transition Out
            val fadeOutStartTime = currentTimeMillis()
            while (currentTimeMillis() - fadeOutStartTime < param.transitionOutMs) {
                val progress = 1.0f - (currentTimeMillis() - fadeOutStartTime).toFloat() / param.transitionOutMs
                manager.setParameterValue(param.id, max(0f, progress * param.value), param.weight)
                delay(16)
            }
            manager.setParameterValue(param.id, 0f, param.weight)
        }
    }

    /** 将 Agent 的 ParameterSet 转换为 Live2D 本地 ParameterSet */
    private fun AgentParameterSet.toLocalParameterSet(): ParameterSet {
        return ParameterSet(
            id = id,
            value = value,
            weight = blend ?: 1f,
            transitionInMs = (duration ?: 1000L) / 3,
            holdMs = (duration ?: 1000L) / 3,
            transitionOutMs = (duration ?: 1000L) / 3
        )
    }
}
