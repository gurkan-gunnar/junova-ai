ARG LLAMA_IMAGE=ghcr.io/ggml-org/llama.cpp:server-cuda

FROM alpine:3.21 AS model
ARG MODEL_URL=https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf?download=true
ARG MODEL_SHA256=626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d
RUN apk add --no-cache ca-certificates curl \
    && mkdir -p /models \
    && curl --fail --location --retry 5 --output /models/Qwen2.5-3B-Instruct-Q4_K_M.gguf "$MODEL_URL" \
    && echo "$MODEL_SHA256  /models/Qwen2.5-3B-Instruct-Q4_K_M.gguf" | sha256sum -c -

FROM ${LLAMA_IMAGE}
COPY --from=model /models/Qwen2.5-3B-Instruct-Q4_K_M.gguf /models/Qwen2.5-3B-Instruct-Q4_K_M.gguf
ENV LLAMA_ARG_MODEL=/models/Qwen2.5-3B-Instruct-Q4_K_M.gguf \
    LLAMA_ARG_ALIAS=junova-qwen-3b \
    LLAMA_ARG_CTX_SIZE=4096 \
    LLAMA_ARG_HOST=0.0.0.0 \
    LLAMA_ARG_PORT=8080 \
    LLAMA_ARG_N_PARALLEL=1 \
    LLAMA_ARG_ENDPOINT_METRICS=1 \
    LLAMA_ARG_CACHE_PROMPT=1 \
    LLAMA_ARG_UI=0
EXPOSE 8080
