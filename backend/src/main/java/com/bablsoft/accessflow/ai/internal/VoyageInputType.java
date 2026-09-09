package com.bablsoft.accessflow.ai.internal;

/**
 * Voyage AI's {@code input_type} request parameter (AF-918). Voyage prepends a different instruction
 * prompt per value, so the same text embeds differently depending on whether it is being stored or
 * searched for; Voyage's own guidance is explicit that the parameter must not be omitted. It is not
 * part of the OpenAI wire format, which is precisely why an {@code OPENAI_COMPATIBLE} row pointed at
 * Voyage embeds below the model's retrieval optimum.
 */
enum VoyageInputType {

    /** Text being indexed into the vector store. */
    DOCUMENT("document"),

    /** Text being searched with. */
    QUERY("query");

    private final String wireValue;

    VoyageInputType(String wireValue) {
        this.wireValue = wireValue;
    }

    String wireValue() {
        return wireValue;
    }
}
