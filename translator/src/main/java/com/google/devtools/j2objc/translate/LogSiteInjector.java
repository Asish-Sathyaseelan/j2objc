/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.j2objc.translate;

import com.google.common.base.Ascii;
import com.google.common.collect.Lists;
import com.google.devtools.j2objc.ast.CompilationUnit;
import com.google.devtools.j2objc.ast.Expression;
import com.google.devtools.j2objc.ast.MethodInvocation;
import com.google.devtools.j2objc.ast.SimpleName;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.UnitTreeVisitor;
import com.google.devtools.j2objc.types.ExecutablePair;
import com.google.devtools.j2objc.util.ElementUtil;
import com.google.devtools.j2objc.util.TypeUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

/**
 * Adds call site information to logging statements, for use by the <a
 * href="https://github.com/google/flogger">Flogger library</a>.
 *
 * <p>Calls to {@code logger.log("hello world")} (requires flogger.jar on j2objc's classpath) are
 * rewritten as:
 *
 * <pre>{@code
 * logger.withInjectedLogSiteMethod(<class>, <method>, <line>, <file>).log("hello world");
 * }</pre>
 *
 * <p>Calls to {@code GoogleLogger.forEnclosingClass()} (requires Flogger's google-extensions
 * classes on j2objc's classpath) are rewritten as:
 *
 * <pre>{@code
 * GoogleLogger.forInjectedClassName(<class>)
 * }</pre>
 *
 * @author Tom Ball
 */
public final class LogSiteInjector extends UnitTreeVisitor {

  private final TypeElement loggingApiClass;
  private final TypeElement googleLoggerClass;
  private final TypeElement javaUtilLoggingLoggerClass;
  private final TypeElement javaUtilLoggingLevelClass;

  private final Map<Integer, Integer> lineCounts = new LinkedHashMap<>();

  private static final String LOGGING_API_CLASS = "com.google.common.flogger.LoggingApi";
  private static final String GOOGLE_LOGGER_CLASS = "com.google.common.flogger.GoogleLogger";

  private static final String LOG_METHOD = "log";
  private static final String LOGP_METHOD = "logp";
  private static final String LOG_VARARGS_METHOD = "logVarargs";
  private static final String WITH_INJECTED_LOG_SITE_METHOD = "withInjectedLogSite";
  private static final List<String> CONVENIENCE_METHODS =
      Lists.newArrayList("severe", "warning", "info", "config", "fine", "finer", "finest");

  public LogSiteInjector(CompilationUnit unit) {
    super(unit);
    loggingApiClass = typeUtil.resolveJavaType(LOGGING_API_CLASS);
    googleLoggerClass = typeUtil.resolveJavaType(GOOGLE_LOGGER_CLASS);
    javaUtilLoggingLoggerClass = typeUtil.resolveJavaType("java.util.logging.Logger");
    javaUtilLoggingLevelClass = typeUtil.resolveJavaType("java.util.logging.Level");
  }

  public boolean isEnabled() {
    return loggingApiClass != null || googleLoggerClass != null;
  }

  @Override
  public void endVisit(MethodInvocation node) {
    ExecutableElement method = node.getExecutableElement();
    String methodName = ElementUtil.getName(method);
    TypeElement cls = ElementUtil.getDeclaringClass(method);

    if (methodName.equals("forEnclosingClass")
        && typeUtil.isSubtype(cls.asType(), googleLoggerClass.asType())) {
      node.replaceWith(injectEnclosingClass(node, cls));
      return;
    }

    if (methodName.equals(LOG_METHOD)
        && typeUtil.isSubtype(cls.asType(), javaUtilLoggingLoggerClass.asType())) {
      node.replaceWith(injectLogMethod(node));
      return;
    }

    if (CONVENIENCE_METHODS.contains(methodName)
        && typeUtil.isSubtype(cls.asType(), javaUtilLoggingLoggerClass.asType())) {
      node.replaceWith(injectConvenienceMethod(methodName, node));
      return;
    }
  }

  private MethodInvocation injectLogMethod(MethodInvocation node) {
    ExecutableElement method = node.getExecutableElement();
    TypeElement cls = ElementUtil.getDeclaringClass(method);
    List<Expression> args = node.getArguments();
    if (!args.isEmpty()
        && typeUtil.isSubtype(args.get(0).getTypeMirror(), javaUtilLoggingLevelClass.asType())) {
      // Change log to logp, insert class and method name args.
      List<Expression> logpArgs = new ArrayList<>();
      List<String> argTypes = new ArrayList<>();
      logpArgs.add(args.get(0).copy());
      argTypes.add("java.util.logging.Level");

      logpArgs.add(enclosingClassLiteral(node));
      argTypes.add("java.lang.String");
      logpArgs.add(enclosingMethodLiteral(node));
      argTypes.add("java.lang.String");

      for (int i = 1; i < args.size(); i++) {
        Expression arg = args.get(i);
        logpArgs.add(arg.copy());
        argTypes.add(TypeUtil.getQualifiedName(arg.getTypeMirror()));
      }

      ExecutableElement injectedMethod =
          ElementUtil.findMethod(cls, LOGP_METHOD, argTypes.toArray(new String[0]));
      MethodInvocation injectedInvocation =
          new MethodInvocation(new ExecutablePair(injectedMethod), node.getExpression().copy());
      for (int i = 0; i < logpArgs.size(); i++) {
        injectedInvocation.addArgument(logpArgs.get(i));
      }
      return injectedInvocation;
    }
    return node.copy();
  }

  private MethodInvocation injectConvenienceMethod(String name, MethodInvocation node) {
    ExecutableElement method = node.getExecutableElement();
    TypeElement cls = ElementUtil.getDeclaringClass(method);

    // Change method name to logp, specify level, and insert class and method name args.
    List<Expression> logpArgs = Lists.newArrayList();
    logpArgs.add(logLevelName(name));
    logpArgs.add(enclosingClassLiteral(node));
    logpArgs.add(enclosingMethodLiteral(node));
    logpArgs.add(node.getArguments().get(0).copy());

    ExecutableElement injectedMethod =
        ElementUtil.findMethod(
            cls,
            LOGP_METHOD,
            "java.util.logging.Level",
            "java.lang.String",
            "java.lang.String",
            "java.lang.String");
    MethodInvocation injectedInvocation =
        new MethodInvocation(new ExecutablePair(injectedMethod), node.getExpression().copy());
    for (int i = 0; i < logpArgs.size(); i++) {
      injectedInvocation.addArgument(logpArgs.get(i));
    }
    return injectedInvocation;
  }

  private MethodInvocation injectEnclosingClass(MethodInvocation node, TypeElement cls) {
    ExecutableElement injectedMethod =
        ElementUtil.findMethod(cls, "forInjectedClassName", "java.lang.String");
    MethodInvocation injectedInvocation =
        new MethodInvocation(new ExecutablePair(injectedMethod), node.getExpression().copy());
    injectedInvocation.addArgument(enclosingClassLiteral(node));
    return injectedInvocation;
  }

  private Expression enclosingClassLiteral(MethodInvocation node) {
    String enclosingClass = ElementUtil.getQualifiedName(TreeUtil.getEnclosingTypeElement(node));
    return TreeUtil.newLiteral(enclosingClass, typeUtil);
  }

  private Expression enclosingMethodLiteral(MethodInvocation node) {
    String enclosingMethod =
        ElementUtil.getName(TreeUtil.getEnclosingMethod(node).getExecutableElement());
    return TreeUtil.newLiteral(enclosingMethod, typeUtil);
  }

  // Returns a Name node for a specified java.util.logging.Level level field.
  private Expression logLevelName(String name) {
    assert (CONVENIENCE_METHODS.contains(name));
    VariableElement field =
        ElementUtil.findField(javaUtilLoggingLevelClass, Ascii.toUpperCase(name));
    return new SimpleName(field);
  }
}
