/**
 * src/utils/jsonSchema.ts
 *
 * 轻量 JSON Schema 子集校验器（零运行时依赖）。
 *
 * 仅覆盖本项目工具 schema 实际用到的关键字：
 * type / enum / const / pattern / minLength / maxLength /
 * minimum / maximum / minItems / maxItems / items /
 * properties / required / additionalProperties / anyOf / oneOf / allOf。
 *
 * 设计原则：校验器只产出「人类可读 + AI 可读」的错误清单，不抛异常，
 * 调用方决定如何处置（本项目里是把错误回灌给模型，让其重传）。
 */

import type { JsonSchemaDefinition, JsonSchemaTypeName } from '@/types'

/** 校验单个值是否满足 schema；返回错误描述数组，空数组表示通过。 */
export function validateAgainstJsonSchema(
  value: unknown,
  schema: JsonSchemaDefinition | undefined,
  path = '',
): string[] {
  if (!schema) return []
  const errors: string[] = []
  validateNode(value, schema, path, errors)
  return errors
}

function matchesType(value: unknown, type: JsonSchemaTypeName): boolean {
  switch (type) {
    case 'string':
      return typeof value === 'string'
    case 'number':
      return typeof value === 'number' && !Number.isNaN(value)
    case 'integer':
      return typeof value === 'number' && Number.isInteger(value)
    case 'boolean':
      return typeof value === 'boolean'
    case 'array':
      return Array.isArray(value)
    case 'object':
      return value !== null && typeof value === 'object' && !Array.isArray(value)
    case 'null':
      return value === null
    default:
      return false
  }
}

function describeType(value: unknown): string {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  return typeof value
}

function looseEquals(a: unknown, b: unknown): boolean {
  if (a === b) return true
  try {
    return JSON.stringify(a) === JSON.stringify(b)
  } catch {
    return false
  }
}

function validateNodeBool(value: unknown, schema: JsonSchemaDefinition): boolean {
  return validateAgainstJsonSchema(value, schema, '').length === 0
}

function validateNode(
  value: unknown,
  schema: JsonSchemaDefinition,
  path: string,
  errors: string[],
): void {
  const typeList = schema.type === undefined
    ? undefined
    : Array.isArray(schema.type)
      ? schema.type
      : [schema.type]

  if (typeList && typeList.length > 0) {
    if (!typeList.some((t) => matchesType(value, t))) {
      errors.push(
        `${path || '值'} 类型应为 ${typeList.join(' | ')}，收到 ${describeType(value)}`,
      )
      // 类型已不匹配，不再下钻，避免噪音错误。
      return
    }
  }

  const effectiveType: JsonSchemaTypeName | undefined = typeList
    ? typeList.find((t) => matchesType(value, t))
    : value === null
      ? 'null'
      : Array.isArray(value)
        ? 'array'
        : (typeof value as JsonSchemaTypeName)

  if (schema.enum !== undefined && !schema.enum.some((e) => looseEquals(e, value))) {
    errors.push(
      `${path || '值'} 必须是枚举之一：${schema.enum.map((e) => JSON.stringify(e)).join(', ')}`,
    )
  }

  if (schema.const !== undefined && !looseEquals(schema.const, value)) {
    errors.push(`${path || '值'} 必须等于 ${JSON.stringify(schema.const)}`)
  }

  if (effectiveType === 'string' && typeof value === 'string') {
    if (schema.minLength !== undefined && value.length < schema.minLength) {
      errors.push(`${path} 长度不能少于 ${schema.minLength}（当前 ${value.length}）`)
    }
    if (schema.maxLength !== undefined && value.length > schema.maxLength) {
      errors.push(`${path} 长度不能超过 ${schema.maxLength}（当前 ${value.length}）`)
    }
    if (schema.pattern !== undefined) {
      try {
        if (!new RegExp(schema.pattern).test(value)) {
          errors.push(`${path} 不匹配格式约束 ${schema.pattern}`)
        }
      } catch {
        // schema 内 pattern 非法时忽略，不阻断主流程。
      }
    }
  }

  if (
    (effectiveType === 'number' || effectiveType === 'integer') &&
    typeof value === 'number'
  ) {
    if (schema.minimum !== undefined && value < schema.minimum) {
      errors.push(`${path} 不能小于 ${schema.minimum}`)
    }
    if (schema.maximum !== undefined && value > schema.maximum) {
      errors.push(`${path} 不能大于 ${schema.maximum}`)
    }
  }

  if (effectiveType === 'array' && Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) {
      errors.push(`${path} 元素数量不能少于 ${schema.minItems}（当前 ${value.length}）`)
    }
    if (schema.maxItems !== undefined && value.length > schema.maxItems) {
      errors.push(`${path} 元素数量不能超过 ${schema.maxItems}（当前 ${value.length}）`)
    }
    if (schema.items) {
      value.forEach((item, i) => {
        validateNode(item, schema.items!, `${path}[${i}]`, errors)
      })
    }
  }

  if (
    effectiveType === 'object' &&
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value)
  ) {
    const obj = value as Record<string, unknown>
    const required = schema.required ?? []
    for (const key of required) {
      if (!(key in obj)) {
        errors.push(`${path ? path + '.' : ''}${key} 为必填项，但缺失`)
      }
    }
    const props = schema.properties ?? {}
    for (const [key, val] of Object.entries(obj)) {
      const childSchema = props[key]
      const childPath = `${path ? path + '.' : ''}${key}`
      if (childSchema) {
        validateNode(val, childSchema, childPath, errors)
      } else if (schema.additionalProperties === false) {
        errors.push(`${childPath} 是未定义的额外属性（schema 不允许额外属性）`)
      } else if (typeof schema.additionalProperties === 'object') {
        validateNode(val, schema.additionalProperties, childPath, errors)
      }
    }
  }

  if (schema.anyOf) {
    const ok = schema.anyOf.some((s) => validateNodeBool(value, s))
    if (!ok) errors.push(`${path || '值'} 不满足 anyOf 中的任一分支`)
  }
  if (schema.oneOf) {
    const matched = schema.oneOf.filter((s) => validateNodeBool(value, s)).length
    if (matched !== 1) {
      errors.push(`${path || '值'} 必须且只能满足 oneOf 中的一个分支（当前满足 ${matched} 个）`)
    }
  }
  if (schema.allOf) {
    for (const s of schema.allOf) validateNode(value, s, path, errors)
  }
}
