package com.example.generator;

import com.squareup.javapoet.ParameterizedTypeName;
import lombok.NonNull;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.*;


public class NonNullValidationProcessor extends AbstractProcessor {

    private Types typeUtils;
    private Elements elementUtils;
    private Filer filer;
    private Messager messager;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        typeUtils = processingEnv.getTypeUtils();
        elementUtils = processingEnv.getElementUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        roundEnv
            .getRootElements()
            .stream()
            .filter(e -> e.getKind() == ElementKind.CLASS)
            .map(e -> (TypeElement) e)
            .filter(e -> !e.getModifiers().contains(Modifier.ABSTRACT))
            .map(TypeElement::getSuperclass)
            .map(ParameterizedTypeName::get)
            .map(t -> (ParameterizedTypeName) t)
            .map(t -> t.typeArguments.get(0))
            .map(t -> elementUtils.getTypeElement(t.toString()))
            .distinct()
            .forEach(t -> {
                new ValidationGenerator(typeUtils, filer, messager, t, elementUtils).generate();
            });

        return true;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return new LinkedHashSet<>(Collections.singleton(NonNull.class.getCanonicalName()));
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }
}
