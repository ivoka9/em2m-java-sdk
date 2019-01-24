package io.em2m.simplex

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.em2m.simplex.model.*
import io.em2m.simplex.parser.ExprParser
import io.em2m.simplex.parser.SimplexModule
import io.em2m.simplex.std.*
import io.em2m.utils.coerce
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap


class Simplex {

    private val keys = BasicKeyResolver()
            .delegate(Numbers.keys)
            .delegate(Dates.keys)
            .delegate(Bools.keys)
            .key(Key("repeat", "*"), PathKeyHandler(this, "repeat"))

    private val pipes = BasicPipeTransformResolver()
            .delegate(Numbers.pipes)
            .delegate(Strings.pipes)
            .delegate(I18n.pipes)
            .delegate(Dates.pipes)
            .delegate(Arrays.pipes)
            .delegate(Bools.pipes(this))

    private val conditions = BasicConditionResolver()
            .delegate(Strings.conditions)
            .delegate(Numbers.conditions)
            .delegate(Bools.conditions)
            .delegate(Dates.conditions)


    private val execs = BasicExecResolver()

    private val cache: ConcurrentMap<String, Expr> = ConcurrentHashMap()

    private val pathExprCache = ConcurrentHashMap<String, PathExpr>()

    val parser = ExprParser(keys, pipes)

    val objectMapper = jacksonObjectMapper().registerModule(SimplexModule(this))

    fun keys(delegate: KeyResolver): Simplex {
        keys.delegate(delegate)
        cache.clear()
        return this
    }

    fun pipes(delegate: PipeTransformResolver): Simplex {
        pipes.delegate(delegate)
        cache.clear()
        return this
    }

    fun conditions(delegate: ConditionResolver): Simplex {
        conditions.delegate(delegate)
        cache.clear()
        return this
    }

    fun execs(delegate: ExecResolver): Simplex {
        execs.delegate(delegate)
        cache.clear()
        return this
    }

    fun exec(exec: Exec, context: ExprContext) {
        // todo - add cache
        val handler = execs.findHandler(exec)
        if (handler != null) {
            val config = exec.config.mapValues { eval(it.value, context) }
            val params = exec.params.mapValues { eval(it.value, context) }
            handler.configure(config)
            handler.call(context, params)
        }
    }

    fun getPath(path: String, context: Any?): Any? {
        val expr = pathExprCache.computeIfAbsent(path) { p -> PathExpr(p) }
        return expr.call(context)
    }

    fun testConditions(conditions: List<Condition>, context: ExprContext): Boolean {
        return compileCondition(conditions).call(context)
    }

    fun compileCondition(conditions: List<Condition>): ConditionExpr {
        return if (conditions.size == 1) {
            getConditionExpr(conditions.first())
        } else {
            MultiConditionExpr(conditions.map { getConditionExpr(it) })
        }
    }

    fun findConditionHandler(name: String): ConditionHandler? {
        return conditions.getCondition(name)
    }

    private fun getConditionExpr(condition: Condition): SingleConditionExpr {
        val op = condition.op
        val keyExpr = parser.parse("$" + "{" + condition.key + "}")
        val values = ArrayExpr(condition.value.map { parser.parse(it) })
        val handler = requireNotNull(conditions.getCondition(op))
        return SingleConditionExpr(op, handler, keyExpr, values)
    }

    private fun getKeyValue(key: String, context: ExprContext): Any? {
        val parsedKey = Key.parse(key)
        val contextKeys: KeyResolver? = context["keys"] as? KeyResolver
        val handler = contextKeys?.find(parsedKey) ?: keys.find(parsedKey)
        return handler?.call(parsedKey, context)
    }

    fun eval(expr: String, context: ExprContext): Any? {
        val parsed = cache.getOrPut(expr, { parser.parse(expr) })
        // safey measure until we implement an extensible caching API
        if (cache.size > 1000) {
            cache.clear()
        }
        return parsed.call(context)
    }

    companion object {
        val simplex = Simplex()
        val objectMapper = jacksonObjectMapper().registerModule(SimplexModule(simplex))
    }

}

fun Any?.evalPath(path: String): Any? {
    return Simplex.simplex.getPath(path, this)
}

inline fun <reified T : Any> Any?.evalPath(path: String, fallback: T?): T? {
    return Simplex.simplex.getPath(path, this).coerce(fallback)
}
