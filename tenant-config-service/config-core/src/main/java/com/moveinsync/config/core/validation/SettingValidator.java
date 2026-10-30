package com.moveinsync.config.core.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.core.model.DataType;
import com.moveinsync.config.core.model.SettingDefinition;
import com.moveinsync.config.core.model.TimeWindow;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class SettingValidator {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static Object validateAndNormalize(SettingDefinition definition, Object rawValue) {
        if (rawValue == null) {
            throw new ValidationException(definition.getKey(), null, "Setting value cannot be null");
        }

        DataType type = definition.getDataType();
        switch (type) {
            case NUMBER:
                return validateNumber(definition, rawValue);

            case DURATION:
                return validateDuration(definition, rawValue);

            case TIME_WINDOW:
                return validateTimeWindow(definition, rawValue);

            case ENUM:
                return validateEnum(definition, rawValue);

            case STRING_LIST:
                return validateStringList(definition, rawValue);

            default:
                throw new ValidationException(definition.getKey(), rawValue, "Unsupported DataType: " + type);
        }
    }

    private static Number validateNumber(SettingDefinition def, Object val) {
        double d;
        if (val instanceof Number) {
            d = ((Number) val).doubleValue();
        } else {
            try {
                d = Double.parseDouble(val.toString().trim());
            } catch (NumberFormatException e) {
                throw new ValidationException(def.getKey(), val, "Must be a valid numeric value");
            }
        }

        if (def.getMinValue() != null && d < def.getMinValue()) {
            throw new ValidationException(def.getKey(), val,
                    "Value " + d + " is less than minimum allowed " + def.getMinValue());
        }
        if (def.getMaxValue() != null && d > def.getMaxValue()) {
            throw new ValidationException(def.getKey(), val,
                    "Value " + d + " is greater than maximum allowed " + def.getMaxValue());
        }
        return (val instanceof Integer || val instanceof Long || (d == Math.floor(d) && !Double.isInfinite(d)))
                ? (long) d
                : d;
    }

    private static Long validateDuration(SettingDefinition def, Object val) {
        long durationMinutes;
        if (val instanceof Number) {
            durationMinutes = ((Number) val).longValue();
        } else {
            try {
                durationMinutes = Long.parseLong(val.toString().trim());
            } catch (NumberFormatException e) {
                throw new ValidationException(def.getKey(), val, "Duration must be an integer representing minutes");
            }
        }

        if (durationMinutes < 0) {
            throw new ValidationException(def.getKey(), val, "Duration cannot be negative (" + durationMinutes + " mins)");
        }
        if (def.getMinValue() != null && durationMinutes < def.getMinValue()) {
            throw new ValidationException(def.getKey(), val,
                    "Duration " + durationMinutes + " is less than minimum " + def.getMinValue().longValue());
        }
        if (def.getMaxValue() != null && durationMinutes > def.getMaxValue()) {
            throw new ValidationException(def.getKey(), val,
                    "Duration " + durationMinutes + " exceeds maximum " + def.getMaxValue().longValue());
        }
        return durationMinutes;
    }

    private static TimeWindow validateTimeWindow(SettingDefinition def, Object val) {
        TimeWindow window;
        if (val instanceof TimeWindow) {
            window = (TimeWindow) val;
        } else if (val instanceof Map) {
            window = OBJECT_MAPPER.convertValue(val, TimeWindow.class);
        } else if (val instanceof String) {
            String str = ((String) val).trim();
            String[] parts = str.split("-");
            if (parts.length != 2) {
                throw new ValidationException(def.getKey(), val, "Time window string must be formatted as 'HH:mm-HH:mm'");
            }
            window = new TimeWindow(parts[0].trim(), parts[1].trim());
        } else {
            throw new ValidationException(def.getKey(), val, "Invalid time window object");
        }

        try {
            LocalTime start = window.parseStart();
            LocalTime end = window.parseEnd();
            if (start.equals(end)) {
                throw new ValidationException(def.getKey(), val, "Time window start and end times cannot be identical");
            }
        } catch (DateTimeParseException e) {
            throw new ValidationException(def.getKey(), val, "Invalid time format in time window, expected HH:mm");
        }

        return window;
    }

    private static String validateEnum(SettingDefinition def, Object val) {
        String str = val.toString().trim();
        List<String> allowed = def.getAllowedValues();
        if (allowed == null || allowed.isEmpty()) {
            return str;
        }
        for (String item : allowed) {
            if (item.equalsIgnoreCase(str)) {
                return item; // Normalize to canonical case
            }
        }
        throw new ValidationException(def.getKey(), val,
                "Value '" + str + "' is not permitted. Allowed values: " + allowed);
    }

    @SuppressWarnings("unchecked")
    private static List<String> validateStringList(SettingDefinition def, Object val) {
        if (val instanceof Collection) {
            return ((Collection<?>) val).stream().map(Object::toString).toList();
        } else if (val instanceof String) {
            String s = ((String) val).trim();
            if (s.isEmpty()) {
                return List.of();
            }
            return List.of(s.split(",\\s*"));
        }
        throw new ValidationException(def.getKey(), val, "Expected list of strings");
    }
}
