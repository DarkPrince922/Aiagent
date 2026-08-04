// JNI-мост к llama.cpp. Написан под C API тега b10259.
//
// Строки передаются массивами байт, а не jstring: GetStringUTFChars отдаёт modified UTF-8,
// в котором символы вне BMP (эмодзи) кодируются суррогатной парой CESU-8. Токенизатор
// такого не ждёт, а пользователь пишет по-русски и вполне может вставить эмодзи.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "JarvisLlm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model        * model = nullptr;
    llama_context      * ctx   = nullptr;
    const llama_vocab  * vocab = nullptr;
    std::atomic<bool>    cancelled{false};
};

Session * session_of(jlong handle) {
    return reinterpret_cast<Session *>(handle);
}

std::string bytes_to_string(JNIEnv * env, jbyteArray value) {
    if (value == nullptr) {
        return {};
    }
    const jsize length = env->GetArrayLength(value);
    std::string result(static_cast<size_t>(length), '\0');
    if (length > 0) {
        env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte *>(result.data()));
    }
    return result;
}

jbyteArray string_to_bytes(JNIEnv * env, const std::string & value) {
    jbyteArray result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result != nullptr && !value.empty()) {
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()),
                                reinterpret_cast<const jbyte *>(value.data()));
    }
    return result;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    // Первый вызов возвращает отрицательное необходимое количество токенов.
    const int32_t needed = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                           nullptr, 0, add_special, /* parse_special */ true);
    if (needed <= 0) {
        return {};
    }
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t written = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                           tokens.data(), needed, add_special, /* parse_special */ true);
    if (written < 0) {
        return {};
    }
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string token_to_text(const llama_vocab * vocab, llama_token token) {
    char buffer[256];
    int32_t length = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, /* special */ false);
    if (length >= 0) {
        return std::string(buffer, static_cast<size_t>(length));
    }
    // Кусок не поместился: length — требуемый размер со знаком минус.
    std::string large(static_cast<size_t>(-length), '\0');
    length = llama_token_to_piece(vocab, token, large.data(), static_cast<int32_t>(large.size()), 0, false);
    if (length < 0) {
        return {};
    }
    large.resize(static_cast<size_t>(length));
    return large;
}

/** Скармливает промпт порциями не больше n_batch: разом длинный контекст llama_decode не примет. */
bool feed_prompt(Session * session, std::vector<llama_token> & tokens) {
    const int32_t batch_size = static_cast<int32_t>(llama_n_batch(session->ctx));
    for (int32_t offset = 0; offset < static_cast<int32_t>(tokens.size()); offset += batch_size) {
        if (session->cancelled.load()) {
            return false;
        }
        const int32_t count = std::min(batch_size, static_cast<int32_t>(tokens.size()) - offset);
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, count);
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama_decode failed while feeding the prompt");
            return false;
        }
    }
    return true;
}

llama_sampler * build_sampler(Session * session, const std::string & grammar,
                              float temperature, float top_p, jint top_k, jint seed) {
    llama_sampler_chain_params chain_params = llama_sampler_chain_default_params();
    chain_params.no_perf = true;
    llama_sampler * chain = llama_sampler_chain_init(chain_params);

    if (!grammar.empty()) {
        // Ленивая грамматика: свободный текст не ограничен, правила включаются только
        // после того, как модель сама начала <tool_call>.
        static const char * pattern = "[\\s\\S]*?(<tool_call>[\\s\\S]*)";
        llama_sampler * grammar_sampler = llama_sampler_init_grammar_lazy_patterns(
            session->vocab, grammar.c_str(), "root", &pattern, 1, nullptr, 0);
        if (grammar_sampler != nullptr) {
            llama_sampler_chain_add(chain, grammar_sampler);
        } else {
            LOGE("grammar was rejected by llama.cpp, continuing unconstrained");
        }
    }

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
        return chain;
    }
    if (top_k > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
    }
    if (top_p > 0.0f && top_p < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
    }
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    return chain;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_app_jarvis_llm_LlamaBridge_nativeInit(JNIEnv *, jobject) {
    llama_backend_init();
    llama_log_set([](ggml_log_level level, const char * text, void *) {
        if (level == GGML_LOG_LEVEL_ERROR) {
            LOGE("%s", text);
        }
    }, nullptr);
}

JNIEXPORT jlong JNICALL
Java_app_jarvis_llm_LlamaBridge_nativeLoad(JNIEnv * env, jobject, jbyteArray path_utf8,
                                           jint context_tokens, jint threads) {
    const std::string path = bytes_to_string(env, path_utf8);
    if (path.empty()) {
        return 0;
    }

    llama_model_params model_params = llama_model_default_params();
    // Никакой выгрузки на GPU: мобильные бэкенды для этого не собраны, всё считает CPU.
    model_params.n_gpu_layers = 0;
    // mmap без mlock: страницы модели подтягиваются по мере надобности и система может их
    // вытеснить под давлением памяти — на телефоне это важнее скорости первого прохода.
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model * model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        LOGE("failed to load model from %s", path.c_str());
        return 0;
    }

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx           = static_cast<uint32_t>(context_tokens);
    context_params.n_batch         = 512;
    context_params.n_ubatch        = 128;
    context_params.n_threads       = threads;
    context_params.n_threads_batch = threads;

    llama_context * ctx = llama_init_from_model(model, context_params);
    if (ctx == nullptr) {
        LOGE("failed to create llama context");
        llama_model_free(model);
        return 0;
    }

    auto * session = new Session();
    session->model = model;
    session->ctx   = ctx;
    session->vocab = llama_model_get_vocab(model);
    LOGI("model loaded, context %u tokens", llama_n_ctx(ctx));
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_app_jarvis_llm_LlamaBridge_nativeRelease(JNIEnv *, jobject, jlong handle) {
    Session * session = session_of(handle);
    if (session == nullptr) {
        return;
    }
    llama_free(session->ctx);
    llama_model_free(session->model);
    delete session;
}

JNIEXPORT jint JNICALL
Java_app_jarvis_llm_LlamaBridge_nativeContextTokens(JNIEnv *, jobject, jlong handle) {
    Session * session = session_of(handle);
    return session == nullptr ? 0 : static_cast<jint>(llama_n_ctx(session->ctx));
}

JNIEXPORT jint JNICALL
Java_app_jarvis_llm_LlamaBridge_nativeCountTokens(JNIEnv * env, jobject, jlong handle, jbyteArray text_utf8) {
    Session * session = session_of(handle);
    if (session == nullptr) {
        return -1;
    }
    return static_cast<jint>(tokenize(session->vocab, bytes_to_string(env, text_utf8), true).size());
}

JNIEXPORT void JNICALL
Java_app_jarvis_llm_LlamaBridge_nativeCancel(JNIEnv *, jobject, jlong handle) {
    Session * session = session_of(handle);
    if (session != nullptr) {
        session->cancelled.store(true);
    }
}

JNIEXPORT jbyteArray JNICALL
Java_app_jarvis_llm_LlamaBridge_nativeGenerate(JNIEnv * env, jobject, jlong handle,
                                               jbyteArray prompt_utf8, jbyteArray grammar_utf8,
                                               jint max_tokens, jfloat temperature, jfloat top_p,
                                               jint top_k, jint seed) {
    Session * session = session_of(handle);
    if (session == nullptr) {
        return string_to_bytes(env, "");
    }
    session->cancelled.store(false);

    // Контекст между вызовами не переиспользуется: агент каждый раз присылает полную историю,
    // а частичное совпадение KV-кэша здесь не окупает риска рассинхронизации.
    llama_memory_clear(llama_get_memory(session->ctx), true);

    const std::string prompt = bytes_to_string(env, prompt_utf8);
    std::vector<llama_token> tokens = tokenize(session->vocab, prompt, true);
    if (tokens.empty()) {
        return string_to_bytes(env, "");
    }

    const int32_t context_tokens = static_cast<int32_t>(llama_n_ctx(session->ctx));
    if (static_cast<int32_t>(tokens.size()) + 8 >= context_tokens) {
        LOGE("prompt of %zu tokens does not fit into a context of %d", tokens.size(), context_tokens);
        return nullptr;
    }

    if (!feed_prompt(session, tokens)) {
        return string_to_bytes(env, "");
    }

    llama_sampler * sampler = build_sampler(session, bytes_to_string(env, grammar_utf8),
                                            temperature, top_p, top_k, seed);
    std::string output;
    const int32_t budget = std::min(max_tokens, context_tokens - static_cast<int32_t>(tokens.size()) - 1);
    for (int32_t produced = 0; produced < budget; ++produced) {
        if (session->cancelled.load()) {
            break;
        }
        const llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        if (llama_vocab_is_eog(session->vocab, token)) {
            break;
        }
        output += token_to_text(session->vocab, token);

        llama_token next = token;
        llama_batch batch = llama_batch_get_one(&next, 1);
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama_decode failed during generation");
            break;
        }
    }
    llama_sampler_free(sampler);
    return string_to_bytes(env, output);
}

} // extern "C"
