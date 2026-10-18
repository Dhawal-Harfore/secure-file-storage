package com.dhawal.secure_file_storage.persistence;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class ActionSetConverter implements AttributeConverter<Set<Action>, String> {

    @Override
    public String convertToDatabaseColumn(Set<Action> actions) {
        if (actions == null || actions.isEmpty()) {
            return "";
        }
        return actions.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(","));
    }

    @Override
    public Set<Action> convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) {
            return EnumSet.noneOf(Action.class);
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(Action::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Action.class)));
    }
}
