/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.jedis.v3_0;

import static io.opentelemetry.javaagent.instrumentation.api.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.instrumentation.jedis.v3_0.JedisClientTracer.tracer;
import static net.bytebuddy.matcher.ElementMatchers.is;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import redis.clients.jedis.Connection;
import redis.clients.jedis.commands.ProtocolCommand;

public class JedisConnectionInstrumentation implements TypeInstrumentation {
  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("redis.clients.jedis.Connection");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isMethod()
            .and(named("sendCommand"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("redis.clients.jedis.commands.ProtocolCommand")))
            .and(takesArgument(1, is(byte[][].class))),
        this.getClass().getName() + "$SendCommandAdvice");
    // FIXME: This instrumentation only incorporates sending the command, not processing the
    // result.
  }

  public static class SendCommandAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.This Connection connection,
        @Advice.Argument(0) ProtocolCommand command,
        @Advice.Argument(1) byte[][] args,
        @Advice.Local("otelContext") Context context,
        @Advice.Local("otelScope") Scope scope) {
      context =
          tracer()
              .startSpan(
                  currentContext(),
                  connection,
                  new JedisClientTracer.CommandWithArgs(command, args));
      scope = context.makeCurrent();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Thrown Throwable throwable,
        @Advice.Local("otelContext") Context context,
        @Advice.Local("otelScope") Scope scope) {
      if (scope == null) {
        return;
      }
      scope.close();

      if (throwable != null) {
        tracer().endExceptionally(context, throwable);
      } else {
        tracer().end(context);
      }
    }
  }
}
