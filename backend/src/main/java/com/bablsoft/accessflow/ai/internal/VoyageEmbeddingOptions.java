package com.bablsoft.accessflow.ai.internal;

import org.springframework.ai.embedding.EmbeddingOptions;

/**
 * Per-request options for {@link VoyageEmbeddingModel} (AF-918), carrying the one parameter Spring
 * AI's {@link EmbeddingOptions} has no slot for: {@link VoyageInputType}.
 *
 * <p>Both vector stores hardcode {@code EmbeddingOptions.builder().build()} when they call the
 * model, so these options are never supplied from outside — the model substitutes its own based on
 * which overload it was entered through, and this type is how that decision travels to
 * {@code call()}.
 */
record VoyageEmbeddingOptions(String model, Integer dimensions, VoyageInputType inputType)
        implements EmbeddingOptions {

    static VoyageEmbeddingOptions of(VoyageInputType inputType) {
        return new VoyageEmbeddingOptions(null, null, inputType);
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public Integer getDimensions() {
        return dimensions;
    }
}
