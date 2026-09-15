package com.babylonjs.embedding;

import java.util.Objects;

/**
 * Immutable representation of a JavaScript value supported by {@link JsBridge}.
 *
 * <p>Numbers retain JavaScript's IEEE-754 double representation, including signed zero, NaN, and
 * infinities. Strings retain UTF-16 code units. Object values are live handles, not serialized
 * copies.
 */
public final class JsValue {
    public enum Kind {
        UNDEFINED,
        NULL,
        BOOLEAN,
        NUMBER,
        STRING,
        OBJECT
    }

    private static final JsValue UNDEFINED = new JsValue(Kind.UNDEFINED, false, 0, null, null);
    private static final JsValue NULL = new JsValue(Kind.NULL, false, 0, null, null);

    private final Kind kind;
    private final boolean booleanValue;
    private final double numberValue;
    private final String stringValue;
    private final JsObject objectValue;

    private JsValue(
            Kind kind,
            boolean booleanValue,
            double numberValue,
            String stringValue,
            JsObject objectValue) {
        this.kind = kind;
        this.booleanValue = booleanValue;
        this.numberValue = numberValue;
        this.stringValue = stringValue;
        this.objectValue = objectValue;
    }

    public static JsValue undefined() {
        return UNDEFINED;
    }

    public static JsValue nullValue() {
        return NULL;
    }

    public static JsValue of(boolean value) {
        return new JsValue(Kind.BOOLEAN, value, 0, null, null);
    }

    /** Creates a JavaScript number using its native IEEE-754 double representation. */
    public static JsValue of(double value) {
        return new JsValue(Kind.NUMBER, false, value, null, null);
    }

    public static JsValue of(String value) {
        return new JsValue(
                Kind.STRING, false, 0, Objects.requireNonNull(value, "value"), null);
    }

    public static JsValue of(JsObject value) {
        return new JsValue(
                Kind.OBJECT, false, 0, null, Objects.requireNonNull(value, "value"));
    }

    public Kind getKind() {
        return kind;
    }

    public boolean asBoolean() {
        requireKind(Kind.BOOLEAN);
        return booleanValue;
    }

    public double asNumber() {
        requireKind(Kind.NUMBER);
        return numberValue;
    }

    public String asString() {
        requireKind(Kind.STRING);
        return stringValue;
    }

    public JsObject asObject() {
        requireKind(Kind.OBJECT);
        return objectValue;
    }

    boolean booleanPayload() {
        return booleanValue;
    }

    double numberPayload() {
        return numberValue;
    }

    String stringPayload() {
        return stringValue;
    }

    long objectPayload() {
        return objectValue == null ? 0 : objectValue.nativeHandle();
    }

    private void requireKind(Kind expected) {
        if (kind != expected) {
            throw new IllegalStateException("Expected " + expected + " but was " + kind);
        }
    }
}
