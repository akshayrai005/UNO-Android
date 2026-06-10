package com.uno.game.ui.game

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.uno.game.models.GameState
import com.uno.game.models.Player
import com.uno.game.network.SocketManager

class GameViewModel : ViewModel() {

    private val _gameState = MutableLiveData<GameState>()
    val gameState: LiveData<GameState> = _gameState

    private val _errorMessage = MutableLiveData<String>()
    val errorMessage: LiveData<String> = _errorMessage

    private val _unoEvent = MutableLiveData<String>()
    val unoEvent: LiveData<String> = _unoEvent

    private val _winnerEvent = MutableLiveData<String>()
    val winnerEvent: LiveData<String> = _winnerEvent

    private val _playerFinishedEvent = MutableLiveData<Pair<String,Int>>()
    val playerFinishedEvent: LiveData<Pair<String,Int>> = _playerFinishedEvent

    // Fired when a drawn card is auto-played: Pair(playerId, cardValue)
    private val _cardAutoPlayedEvent = MutableLiveData<Pair<String,String>>()
    val cardAutoPlayedEvent: LiveData<Pair<String,String>> = _cardAutoPlayedEvent

    var currentPlayerId: String = ""
    var currentUsername: String = ""
    var roomCode: String = ""

    fun initSocket() {
        if (!SocketManager.isConnected()) SocketManager.connect()

        SocketManager.onGameStarted = { state ->
            Log.d("GameViewModel", "game_started --- players=${state.players.size}")
            _gameState.postValue(state)
        }
        SocketManager.onGameState = { state ->
            Log.d("GameViewModel", "game_state --- currentPlayer=${state.currentPlayerId}")
            _gameState.postValue(state)
            if (state.status == "finished") {
                state.winner?.takeIf { it.isNotBlank() }?.let { _winnerEvent.postValue(it) }
            }
        }
        SocketManager.onUnoCalled = { playerId ->
            _unoEvent.postValue(playerId)
        }
        SocketManager.onPlayerFinished = { playerId, position ->
            _playerFinishedEvent.postValue(Pair(playerId, position))
        }
        SocketManager.onCardAutoPlayed = { playerId, cardValue ->
            _cardAutoPlayedEvent.postValue(Pair(playerId, cardValue))
        }
        SocketManager.onError = { msg ->
            _errorMessage.postValue(msg)
        }

        SocketManager.requestGameState(roomCode)
    }

    fun playCard(cardId: String, chosenColor: String? = null) =
        SocketManager.playCard(roomCode, currentPlayerId, cardId, chosenColor)

    fun drawCard() =
        SocketManager.drawCard(roomCode, currentPlayerId)

    fun sayUno() =
        SocketManager.sayUno(roomCode, currentPlayerId)

    fun challengeUno(targetId: String) =
        SocketManager.challengeUno(roomCode, currentPlayerId, targetId)

    fun isMyTurn(): Boolean =
        _gameState.value?.currentPlayerId == currentPlayerId

    fun getMyHand() =
        _gameState.value?.players?.find { it.id == currentPlayerId }?.hand ?: emptyList()

    fun getOtherPlayers(): List<Player> =
        _gameState.value?.players?.filter { it.id != currentPlayerId } ?: emptyList()

    override fun onCleared() {
        super.onCleared()
        SocketManager.onGameStarted = null
        SocketManager.onGameState = null
        SocketManager.onUnoCalled = null
        SocketManager.onPlayerFinished = null
        SocketManager.onCardAutoPlayed = null
        SocketManager.onError = null
    }
}
