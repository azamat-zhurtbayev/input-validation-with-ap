package com.example.generator;

import com.example.validation.INonNullValidator;
import com.example.validation.NonNullValidationException;
import com.squareup.javapoet.*;
import lombok.NonNull;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ValidationGenerator {

    public static final String SUFFIX = "NonNullValidator";
    private static final String NULL_FIELDS_VAR = "nullFields";
    private static final String OBJECT_VAR = "object";
    private static final String GET_NON_FILLED_PROPERTIES_METHOD = "getNonFilledProperties";
    private static final String EXTERNAL_OBJECT_VALIDATION_SRC = NULL_FIELDS_VAR + ".addAll(\n" +
                                                                     "  $T.$L($L.$L())\n" +
                                                                     "    .stream()\n" +
                                                                     "    .map(s -> $S + '.' + s)\n" +
                                                                     "    .collect($T.toList())\n" +
                                                                     ")";

    private static final Map<String, String> registeredValidators = new HashMap<>();

    private Types typeUtils;
    private Filer filer;
    private Messager messager;
    private TypeElement rootType;
    private Elements elementUtils;

    public ValidationGenerator(Types typeUtils, Filer filer, Messager messager, TypeElement rootType, Elements elementUtils) {
        this.typeUtils = typeUtils;
        this.filer = filer;
        this.messager = messager;
        this.rootType = rootType;
        this.elementUtils = elementUtils;
    }

    public String generate() {
        if (rootType.getKind() != ElementKind.CLASS) {
            return null;
        }
        if (!registeredValidators.containsKey(rootType.toString())) {
            registeredValidators.put(rootType.toString(), rootType.toString() + SUFFIX);
            try {
                JavaFile javaFile = JavaFile
                                        .builder(
                                            rootType.getEnclosingElement().toString(),
                                            buildType()
                                        ).build();
                javaFile.writeTo(filer);
            } catch (Throwable e) {
                e.printStackTrace();
                messager.printMessage(Diagnostic.Kind.ERROR, String.format("Error while generating validator for class: %s", e.getMessage()), rootType);
            }
        }
        return registeredValidators.get(rootType.toString());
    }

    public TypeSpec buildType() {
        return TypeSpec.classBuilder(rootType.getSimpleName().toString() + SUFFIX)
                       .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                       .addMethod(buildGetNotFilledPropertiesMethodSpec())
                       .addMethod(buildValidateMethodSpec())
                       .addSuperinterface(ParameterizedTypeName.get(ClassName.get(INonNullValidator.class), TypeName.get(rootType.asType())))
                       .build();
    }

    private MethodSpec buildValidateMethodSpec() {
        return MethodSpec.methodBuilder("validate")
                         .addModifiers(Modifier.PUBLIC)
                         .returns(void.class)
                         .addException(NonNullValidationException.class)
                         .addParameter(TypeName.get(rootType.asType()), OBJECT_VAR)
                         .addStatement("$T<$T> $L = $L($L)", List.class, String.class, NULL_FIELDS_VAR, GET_NON_FILLED_PROPERTIES_METHOD, OBJECT_VAR)
                         .beginControlFlow("if (!$L.isEmpty())", NULL_FIELDS_VAR)
                         .addStatement("throw new $T($L)", NonNullValidationException.class, NULL_FIELDS_VAR)
                         .endControlFlow()
                         .build();
    }

    private MethodSpec buildGetNotFilledPropertiesMethodSpec() {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(GET_NON_FILLED_PROPERTIES_METHOD)
                                                     .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                                                     .returns(ParameterizedTypeName.get(List.class, String.class))
                                                     .addParameter(TypeName.get(rootType.asType()), OBJECT_VAR);

        initNullFieldsVar(methodBuilder);

        rootType.getEnclosedElements()
                .stream()
                .filter(e -> e.getKind() == ElementKind.FIELD)
                .map(e -> (VariableElement) e)
                .filter(e -> !TypeName.get(e.asType()).isPrimitive())
                .forEach(ve -> addOwnVariablesValidations(methodBuilder, ve));

        methodBuilder.addStatement("return $L", NULL_FIELDS_VAR);

        return methodBuilder.build();
    }

    private void initNullFieldsVar(MethodSpec.Builder method) {
        TypeMirror superClass = rootType.getSuperclass();
        String superClassValidator = new ValidationGenerator(typeUtils, filer, messager, (TypeElement) typeUtils.asElement(superClass), elementUtils).generate();
        if (superClassValidator != null) {
            method.addStatement(
                "$T<$T> $L = $T.$L($L)",
                List.class, String.class, NULL_FIELDS_VAR,
                ClassName.bestGuess(superClassValidator), GET_NON_FILLED_PROPERTIES_METHOD, OBJECT_VAR
            );
        } else {
            method.addStatement("$T<$T> $L = new $T<>()", List.class, String.class, NULL_FIELDS_VAR, LinkedList.class);
        }
    }

    private void addOwnVariablesValidations(MethodSpec.Builder method, VariableElement ve) {
        String getter = "get" + capitalize(ve.getSimpleName().toString());

        if (ve.getAnnotationsByType(NonNull.class).length == 1) {
            if (TypeKind.ARRAY == ve.asType().getKind()) {
                method.beginControlFlow("if ($1L.$2L() == null || $1L.$2L().length == 0)", OBJECT_VAR, getter);
            } else if (isCollection(ve.asType()) || typeUtils.isAssignable(ve.asType(), elementUtils.getTypeElement(String.class.getCanonicalName()).asType())) {
                method.beginControlFlow("if ($1L.$2L() == null || $1L.$2L().isEmpty())", OBJECT_VAR, getter);
            } else {
                method.beginControlFlow("if ($L.$L() == null)", OBJECT_VAR, getter);
            }

            method.addStatement("$L.add($S)", NULL_FIELDS_VAR, ve.getSimpleName().toString());
            method.endControlFlow();
        }

        if (TypeKind.ARRAY == ve.asType().getKind()) {
            ArrayType array = (ArrayType) ve.asType();
            if (!TypeName.get(array.getComponentType()).isPrimitive()) {
                TypeElement te = (TypeElement) typeUtils.asElement(array.getComponentType());
                addCollectionCheck(te, method, getter, ve);
            }
        } else if (isCollection(ve.asType())) {
            TypeElement te = elementUtils.getTypeElement(((ParameterizedTypeName) ParameterizedTypeName.get(ve.asType())).typeArguments.get(0).toString());
            addCollectionCheck(te, method, getter, ve);
        } else {
            String externalValidator = new ValidationGenerator(typeUtils, filer, messager, (TypeElement) typeUtils.asElement(ve.asType()), elementUtils).generate();
            if (externalValidator != null) {
                method.beginControlFlow("if ($L.$L() != null)", OBJECT_VAR, getter);
                method.addStatement(EXTERNAL_OBJECT_VALIDATION_SRC, ClassName.bestGuess(externalValidator), GET_NON_FILLED_PROPERTIES_METHOD, OBJECT_VAR, getter, ve.getSimpleName(), ClassName.get(Collectors.class));
                method.endControlFlow();
            }
        }
    }

    private void addCollectionCheck(TypeElement te, MethodSpec.Builder method, String getter, VariableElement ve) {
        String externalValidator = new ValidationGenerator(typeUtils, filer, messager, te, elementUtils).generate();
        if (externalValidator != null) {
            method.beginControlFlow("if ($L.$L() != null)", OBJECT_VAR, getter);
            method.addStatement("int i = 0");
            method.beginControlFlow("for ($T t: $L.$L())", te, OBJECT_VAR, getter);
            method.beginControlFlow("for (String s : $T.$L(t))", ClassName.bestGuess(externalValidator), GET_NON_FILLED_PROPERTIES_METHOD);
            method.addStatement("$L.add($S + '[' + i + ']' + '.' + s)", NULL_FIELDS_VAR, ve.getSimpleName());
            method.endControlFlow();
            method.addStatement("i++");
            method.endControlFlow();
            method.endControlFlow();
        }
    }

    private boolean isCollection(TypeMirror t) {
        return (typeUtils.isAssignable(typeUtils.erasure(t), elementUtils.getTypeElement(Collection.class.getCanonicalName()).asType()));
    }

    private String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
