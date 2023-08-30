package com.devinrsmith.aeropress.cli;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ClassInfoList.ClassInfoFilter;
import io.github.classgraph.ClassRefOrTypeVariableSignature;
import io.github.classgraph.ClassRefTypeSignature;
import io.github.classgraph.HasName;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.MethodInfoList.MethodInfoFilter;
import io.github.classgraph.MethodParameterInfo;
import io.github.classgraph.MethodTypeSignature;
import io.github.classgraph.ReferenceTypeSignature;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;
import io.github.classgraph.TypeParameter;
import io.github.classgraph.TypeSignature;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

public class Main {
  public static void main(String[] args) {
    final Path jarPath = Path.of(args[0]);
    if (!jarPath.getFileName().toString().endsWith(".jar")) {
      throw new IllegalArgumentException("Must point to jar");
    }
    final String assumedClasspath = jarPath.getParent().toString() + "/*";
    final URI jarUri = jarPath.toUri();
    final Set<String> excludedAnnotations =
        Set.of("io.deephaven.util.annotations.VisibleForTesting");
    for (URI uri :
        publiclyReachableClasspathElements(assumedClasspath, jarUri, excludedAnnotations)) {
      System.out.println(uri);
    }
  }

  private static Set<URI> publiclyReachableClasspathElements(
      String classpath, URI fromResource, Set<String> excludedAnnotations) {
    final Set<URI> publicClasspathElements = new TreeSet<>();
    final ClassGraph classGraph =
        new ClassGraph()
            .overrideClasspath(classpath)
            .enableClassInfo()
            .enableFieldInfo()
            .enableMethodInfo();
    if (!excludedAnnotations.isEmpty()) {
      classGraph.enableAnnotationInfo();
    }
    try (final ScanResult scanResult = classGraph.scan()) {
      final ClassInfoFilter classInfoFilter =
          classInfo ->
              hasClasspathElement(classInfo, fromResource)
                  && !hasAnyAnnotation(classInfo, excludedAnnotations);
      final MethodInfoFilter methodInfoFilter =
          methodInfo -> !hasAnyAnnotation(methodInfo, excludedAnnotations);
      visitAllTypeSignatures(
          scanResult,
          classInfoFilter,
          methodInfoFilter,
          classInfo -> {
            final Resource resource = classInfo.getResource();
            // todo: what does null mean in this context?
            if (resource != null) {
              publicClasspathElements.add(resource.getClasspathElementURI());
            }
          });
      return publicClasspathElements;
    }
  }

  private static boolean hasClasspathElement(ClassInfo classInfo, URI uri) {
    return hasClasspathElement(classInfo.getResource(), uri);
  }

  private static boolean hasClasspathElement(Resource resource, URI uri) {
    return uri.equals(resource.getClasspathElementURI());
  }

  private static boolean hasAnyAnnotation(ClassInfo classInfo, Set<String> names) {
    return !names.isEmpty() && hasAnyName(classInfo.getAnnotationInfo(), names);
  }

  private static boolean hasAnyAnnotation(MethodInfo methodInfo, Set<String> names) {
    return !names.isEmpty() && hasAnyName(methodInfo.getAnnotationInfo(), names);
  }

  private static <T extends HasName> boolean hasAnyName(
      Collection<T> collection, Set<String> names) {
    return collection.stream().map(HasName::getName).anyMatch(names::contains);
  }

  private static void visitAllTypeSignatures(
      ScanResult scanResult,
      ClassInfoFilter classInfoFilter,
      MethodInfoFilter methodInfoFilter,
      Consumer<ClassInfo> consumer) {

    final Consumer<TypeSignature> toClassInfo =
        typeSignature -> {
          if (typeSignature instanceof final ClassRefTypeSignature x) {
            final ClassInfo classInfo = x.getClassInfo();
            // todo: why null sometimes?
            if (classInfo != null) {
              consumer.accept(classInfo);
            }
          }
        };

    for (final ClassInfo classInfo : scanResult.getAllClasses().filter(classInfoFilter)) {
      for (final MethodInfo methodInfo :
          classInfo.getMethodAndConstructorInfo().filter(methodInfoFilter)) {

        MethodTypeSignature signature = null;
        try {
          signature = methodInfo.getTypeSignatureOrTypeDescriptor();
        } catch (IllegalArgumentException e) {
          if (!"Bad typePathKind: 0".equals(e.getMessage())) {
            // todo: file bug w/ classgraph?
            throw e;
          }
        }
        if (signature != null) {
          toClassInfo.accept(signature.getResultType());

          // for some reason, this seems to be empty
          for (TypeParameter typeParameter : signature.getTypeParameters()) {
            toClassInfo.accept(typeParameter.getClassBound());
            for (ReferenceTypeSignature interfaceBound : typeParameter.getInterfaceBounds()) {
              toClassInfo.accept(interfaceBound);
            }
          }

          // for some reason, this seems to be empty, even if methodInfo.getThrownExceptions() is
          // not....
          for (ClassRefOrTypeVariableSignature throwsSignature : signature.getThrowsSignatures()) {
            toClassInfo.accept(throwsSignature);
          }
        }

        // https://github.com/classgraph/classgraph/wiki/TypeSignature-API#example-usage
        // Note: don't understand the api well enough to know why this info isn't part of
        // MethodTypeSignature...
        for (final MethodParameterInfo methodParameterInfo : methodInfo.getParameterInfo()) {
          toClassInfo.accept(methodParameterInfo.getTypeSignatureOrTypeDescriptor());
        }

        for (ClassInfo thrownException : methodInfo.getThrownExceptions()) {
          consumer.accept(thrownException);
        }
      }
    }
  }
}
