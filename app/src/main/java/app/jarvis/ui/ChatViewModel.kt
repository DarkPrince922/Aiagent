package app.jarvis.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.jarvis.data.ChatRepository
import app.jarvis.data.DeliveryState
import app.jarvis.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class Confirmation(val label: String, val run: () -> Unit)
data class ChatState(val messages: List<Message> = emptyList(), val sending: Boolean = false, val confirmations: List<Confirmation> = emptyList())

class ChatViewModel(private val repository: ChatRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ChatState())
    val state = mutable.asStateFlow()

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isBlank() || mutable.value.sending) return
        val user = Message(role = "user", text = text, state = DeliveryState.SENDING)
        mutable.value = mutable.value.copy(messages = mutable.value.messages + user, sending = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.send(mutable.value.messages) }
            result.fold(
                onSuccess = { answer ->
                    val sent = mutable.value.messages.map { if (it.id == user.id) it.copy(state = DeliveryState.SENT) else it }
                    mutable.value = ChatState(sent + Message(role = "assistant", text = answer.text), confirmations = answer.confirmations.mapNotNull { result -> result.action?.let { Confirmation(result.text, it) } })
                },
                onFailure = { error ->
                    if (error is IOException) {
                        repository.queue(text)
                        val queued = mutable.value.messages.map { if (it.id == user.id) it.copy(state = DeliveryState.QUEUED) else it }
                        mutable.value = ChatState(queued + Message(role = "assistant", text = "Связь пропала. Запрос сохранён и будет отправлен после восстановления сети."))
                    } else {
                        val failed = mutable.value.messages.map { if (it.id == user.id) it.copy(state = DeliveryState.FAILED) else it }
                        mutable.value = ChatState(failed + Message(role = "assistant", text = error.message ?: "Неизвестная ошибка"))
                    }
                }
            )
        }
    }

    fun confirm(item: Confirmation) {
        item.run()
        mutable.value = mutable.value.copy(confirmations = mutable.value.confirmations - item)
    }
}
