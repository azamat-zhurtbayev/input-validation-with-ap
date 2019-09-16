package com.example.validation;

public interface INonNullValidator<T> {

    void validate(T object) throws NonNullValidationException;

}
