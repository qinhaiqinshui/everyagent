import type { LLMMessage, LLMToolCall } from '@/types'

function cloneToolCall(toolCall: LLMToolCall): LLMToolCall {
  return {
    ...toolCall,
    function: {
      ...toolCall.function,
    },
  }
}

export function cloneLLMMessage(message: LLMMessage): LLMMessage {
  if (message.role === 'assistant') {
    return {
      ...message,
      ...(message.tool_calls
        ? { tool_calls: message.tool_calls.map(cloneToolCall) }
        : {}),
    }
  }

  if (message.role === 'tool') {
    return {
      ...message,
    }
  }

  return {
    ...message,
  }
}

export function cloneLLMMessages(messages: LLMMessage[] | undefined): LLMMessage[] {
  return (messages ?? []).map(cloneLLMMessage)
}
