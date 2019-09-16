package com.example.validation;

import java.util.List;
import java.util.stream.Collectors;

public class NonNullValidationException extends Exception {

    public NonNullValidationException(List<String> fields) {
        super(
            String.format(
                "Following required fields are null : %s",
                String.join(", ", fields)
            )
        );
    }

}
