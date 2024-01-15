// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.embedding;

import ai.vespa.modelintegration.evaluator.OnnxEvaluator;
import ai.vespa.modelintegration.evaluator.OnnxEvaluatorOptions;
import ai.vespa.modelintegration.evaluator.OnnxRuntime;
import com.yahoo.api.annotations.Beta;
import com.yahoo.component.AbstractComponent;
import com.yahoo.component.annotation.Inject;
import com.yahoo.embedding.SpladeEmbedderConfig;
import com.yahoo.language.huggingface.HuggingFaceTokenizer;
import com.yahoo.language.process.Embedder;
import com.yahoo.tensor.IndexedTensor;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.TensorType;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static com.yahoo.language.huggingface.ModelInfo.TruncationStrategy.LONGEST_FIRST;

/**
 * A SPLADE embedder that is embedding text to a 1-d mapped tensor. For interpretability, the tensor labels
 * are the subword strings from the wordpiece vocabulary that has a score above a threshold (default 0.0).
 */
@Beta
public class SpladeEmbedder extends AbstractComponent implements Embedder {
    private final Embedder.Runtime runtime;
    private final String inputIdsName;
    private final String attentionMaskName;
    private final String tokenTypeIdsName;
    private final String outputName;
    private final double termScoreThreshold;
    private final HuggingFaceTokenizer tokenizer;
    private final OnnxEvaluator evaluator;

    @Inject
    public SpladeEmbedder(OnnxRuntime onnx, Embedder.Runtime runtime, SpladeEmbedderConfig config) {
        this.runtime = runtime;
        inputIdsName = config.transformerInputIds();
        attentionMaskName = config.transformerAttentionMask();
        outputName = config.transformerOutput();
        tokenTypeIdsName = config.transformerTokenTypeIds();
        termScoreThreshold = config.termScoreThreshold();

        var tokenizerPath = Paths.get(config.tokenizerPath().toString());
        var builder = new HuggingFaceTokenizer.Builder()
                .addSpecialTokens(true)
                .addDefaultModel(tokenizerPath)
                .setPadding(false);
        var info = HuggingFaceTokenizer.getModelInfo(tokenizerPath);
        if (info.maxLength() == -1 || info.truncation() != LONGEST_FIRST) {
            // Force truncation
            // to max length accepted by model if tokenizer.json contains no valid truncation configuration
            int maxLength = info.maxLength() > 0 && info.maxLength() <= config.transformerMaxTokens()
                    ? info.maxLength()
                    : config.transformerMaxTokens();
            builder.setTruncation(true).setMaxLength(maxLength);
        }
        this.tokenizer = builder.build();
        var onnxOpts = new OnnxEvaluatorOptions();

        if (config.transformerGpuDevice() >= 0)
            onnxOpts.setGpuDevice(config.transformerGpuDevice());
        onnxOpts.setExecutionMode(config.transformerExecutionMode().toString());
        onnxOpts.setThreads(config.transformerInterOpThreads(), config.transformerIntraOpThreads());
        evaluator = onnx.evaluatorOf(config.transformerModel().toString(), onnxOpts);
        validateModel();
    }

    public void validateModel() {
        Map<String, TensorType> inputs = evaluator.getInputInfo();
        validateName(inputs, inputIdsName, "input");
        validateName(inputs, attentionMaskName, "input");
        Map<String, TensorType> outputs = evaluator.getOutputInfo();
        validateName(outputs, outputName, "output");
    }

    /**
     * Validates that the given tensor type is a 1-d mapped tensor.
     *
     * @param target the type to validate
     * @return true if the type is a 1-d mapped tensor
     */
    protected boolean verifyTensorType(TensorType target) {
        return target.dimensions().size() == 1 && target.dimensions().get(0).isMapped();
    }

    private void validateName(Map<String, TensorType> types, String name, String type) {
        if (!types.containsKey(name)) {
            throw new IllegalArgumentException("Model does not contain required " + type + ": '" + name + "'. " +
                    "Model contains: " + String.join(",", types.keySet()));
        }
    }

    @Override
    public List<Integer> embed(String text, Context context) {
        throw new UnsupportedOperationException("This embedder only supports embed with tensor type");
    }

    @Override
    public Tensor embed(String text, Context context, TensorType tensorType) {
        if (!verifyTensorType(tensorType)) {
            throw new IllegalArgumentException("Invalid splade embedder tensor destination. " +
                    "Wanted a mapped 1-d tensor, got " + tensorType);
        }
        var start = System.nanoTime();

        var encoding = tokenizer.encode(text, context.getLanguage());
        runtime.sampleSequenceLength(encoding.ids().size(), context);

        Tensor inputSequence = createTensorRepresentation(encoding.ids(), "d1");
        Tensor attentionMask = createTensorRepresentation(encoding.attentionMask(), "d1");
        Tensor tokenTypeIds = createTensorRepresentation(encoding.typeIds(), "d1");

        Map<String, Tensor> inputs = Map.of(inputIdsName, inputSequence.expand("d0"),
                attentionMaskName, attentionMask.expand("d0"),
                tokenTypeIdsName, tokenTypeIds.expand("d0"));
        Tensor spladeTensor = sparsify((IndexedTensor) evaluator.evaluate(inputs).get(outputName), tensorType);
        runtime.sampleEmbeddingLatency((System.nanoTime() - start)/1_000_000d, context);
        return spladeTensor;
    }


    /**
     * Sparsify the model output tensor.
     *
     * @param modelOutput the model output tensor of type tensorType
     * @param tensorType the type of the destination tensor
     * @return A mapped tensor with the terms from the vocab that has a score above the threshold
     */
    public Tensor sparsify(IndexedTensor modelOutput, TensorType tensorType) {
        var builder = Tensor.Builder.of(tensorType);
        long[] shape = modelOutput.shape();
        if(shape.length != 3) {
            throw new IllegalArgumentException("The indexed tensor must be 3-dimensional");
        }
        long batch = shape[0];
        if (batch != 1) {
            throw new IllegalArgumentException("Batch size must be 1");
        }
        long sequenceLength = shape[1];
        long vocabSize = shape[2];

        //Iterate over the vocab dimension and find the max value for each sequence token
        for(int v = 0; v < vocabSize; v++) {
            double maxLogOfRelu = Double.MIN_VALUE;
            for(int s = 0; s < sequenceLength; s++) {
                double value = modelOutput.get(0, s, v); // batch, sequence, vocab
                double logOfRelu = Math.log(1 + Math.max(0, value));
                if(logOfRelu > maxLogOfRelu) {
                    maxLogOfRelu = logOfRelu;
                }
            }
            if (maxLogOfRelu > termScoreThreshold) {
                String term = tokenizer.decode(List.of((long) v));
                builder.cell().
                        label(tensorType.dimensions().get(0).name(), term)
                        .value(maxLogOfRelu);
            }
        }
        return builder.build();
    }

    private IndexedTensor createTensorRepresentation(List<Long> input, String dimension) {
        int size = input.size();
        TensorType type = new TensorType.Builder(TensorType.Value.FLOAT).indexed(dimension, size).build();
        IndexedTensor.Builder builder = IndexedTensor.Builder.of(type);
        for (int i = 0; i < size; ++i) {
            builder.cell(input.get(i), i);
        }
        return builder.build();
    }
    @Override
    public void deconstruct() {
        evaluator.close();
        tokenizer.close();
    }

}