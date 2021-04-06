package ch.zxseitz.tbsg.server.websocket;

import ch.zxseitz.tbsg.TbsgException;
import ch.zxseitz.tbsg.games.Color;
import ch.zxseitz.tbsg.games.GameState;
import ch.zxseitz.tbsg.games.exceptions.ClientException;
import ch.zxseitz.tbsg.server.games.GameProxy;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

public class GameSocketHandler extends TextWebSocketHandler {
    private final Logger logger;
    private final GameProxy proxy;
    private final Map<String, Client> clients;

    public GameSocketHandler(GameProxy proxy) {
        this.logger = LoggerFactory.getLogger(GameSocketHandler.class.getName() + ":" + proxy.getName());
        this.proxy = proxy;
        this.clients = new ConcurrentHashMap<>();
    }

    /**
     * Performs a critical section on several locks
     *
     * @param callable critical section
     * @param locks list of lock
     * @return critical section return value
     * @throws Exception if an exception occurs during the critical section
     */
    @SafeVarargs
    private static <T, L> T safe(Callable<T> callable, IProtectable<L>... locks) throws Exception {
        var sorted = Arrays.stream(locks).sorted().collect(Collectors.toList());  //prevent deadlocks
        sorted.forEach(IProtectable::lock);
        try {
            return callable.call();
        } finally {
            sorted.forEach(IProtectable::unlock);
        }
    }

    /**
     * Handles client connection
     *
     * @param session client websocket session
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("Connected client: {}", session.getId());
        // todo: read auth infos
        var client = new Client(session);
        clients.put(session.getId(), client);
        sendToClient(client, MessageManager.createIdMessage(client));
    }

    /**
     * Handles client disconnection
     *
     * @param session client websocket session
     * @param status close status
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        logger.info("Disconnected client: {}", session.getId());
        var client = clients.remove(session.getId());
        // todo delete challenges and abort open matches
    }

    /**
     * Handles client events
     *
     * @param session client websocket session
     * @param message client event
     */
    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        var client = clients.get(session.getId());

        try {
            var codeNode = MessageManager.parseClientMessage(message.getPayload());
            var messageCode = codeNode.getKey();
            var argNode = codeNode.getValue();
            // lobby client events
            switch (messageCode) {
                case MessageManager.CLIENT_CHALLENGE: {
                    var opponentId = MessageManager.readClientArgument(argNode, "opponent", String.class);
                    var opponent = clients.get(opponentId);
                    if (opponent == null) {
                        throw new TbsgException(String.format("Opponent [%s] is not connected", opponentId));
                    }
                    safe(() -> {
                        client.getChallenges().add(opponent);
                        opponent.send(MessageManager.createChallengeMessage(client));
                        return null;
                    }, client);
                    break;
                }
                case MessageManager.CLIENT_CHALLENGE_ABORT: {
                    var opponentId = MessageManager.readClientArgument(argNode, "opponent", String.class);
                    var opponent = clients.get(opponentId);
                    if (opponent == null) {
                        throw new TbsgException(String.format("Opponent [%s] is not connected", opponentId));
                    }
                    safe(() -> {
                        if (client.getChallenges().remove(opponent)) {
                            opponent.send(MessageManager.createChallengeAbortMessage(client));
                        } else {
                            throw new TbsgException(String.format("Opponent [%s] is not challenged by you", opponentId));
                        }
                        return null;
                    }, client);
                    break;
                }
                case MessageManager.CLIENT_CHALLENGE_ACCEPT: {
                    var opponentId = MessageManager.readClientArgument(argNode, "opponent", String.class);
                    var opponent = clients.get(opponentId);
                    if (opponent == null) {
                        throw new TbsgException(String.format("Opponent [%s] is not connected", opponentId));
                    }
                    safe(() -> {
                        // todo queuing accepts
                        if (client.getMatch() == null && opponent.getMatch() == null) {
                            opponent.send(MessageManager.createChallengeAcceptMessage(client));
                            var board = proxy.createGame();
                            var match = new Match(new Protector<>(board), client, opponent);
                            client.setMatch(match);
                            opponent.setMatch(match);

                            sendToClient(client, MessageManager
                                    .createGameInitNextMessage(Color.BLACK, null, null));  // fixme
                            sendToClient(opponent, MessageManager
                                    .createGameInitMessage(Color.WHITE, null));  // fixme
                        } else {
                            throw new TbsgException(String.format("You or opponent [%s] is currently in game", opponentId));
                        }
                        return null;
                    }, client, opponent);
                    break;
                }
                case MessageManager.CLIENT_CHALLENGE_DECLINE: {
                    var opponentId = MessageManager
                            .readClientArgument(argNode, "opponent", String.class);
                    var opponent = clients.get(opponentId);
                    if (opponent == null) {
                        throw new TbsgException(String.format("Opponent [%s] is not connected", opponentId));
                    }
                    safe(() -> {
                        if (opponent.getChallenges().remove(client)) {
                            opponent.send(MessageManager.createChallengeDeclineMessage(client));
                        } else {
                            throw new TbsgException(String.format("Opponent [%s] is not challenged by you", opponentId));
                        }
                        return null;
                    }, opponent);
                    break;
                }
                case MessageManager.CLIENT_UPDATE: {
                    var arguments = MessageManager
                            .readClientGameArguments(argNode, proxy.getUpdateArguments());
                    safe(() ->  {
                        var match = client.getMatch();
                        var gameProtector = match.getGame();
                        var opponent = match.getOpponent(client);
                        safe(() ->  {
                            var game = gameProtector.get();
                            if (game.getState() == GameState.FINISHED) {
                                sendToClient(client, MessageManager
                                        .createErrorMessage("Game is already finished"));
                            }
                            var color = match.getColor(client);
                            if (!game.getNext().equals(color)) {
                                sendToClient(client, MessageManager
                                        .createErrorMessage("Not your turn"));
                            }

                            proxy.performUpdate(game, arguments);

                            if (game.getState().equals(GameState.RUNNING)) {
                                // game continues
                                var updateNextMessage = MessageManager
                                        .createGameUpdateNextMessage(0, null, null); //fixme
                                var updateMessage = MessageManager
                                        .createGameUpdateMessage(0, null); //fixme
                                if (game.getNext().equals(color)) {
                                    sendToClient(client, updateNextMessage);
                                    sendToClient(opponent, updateMessage);
                                } else {
                                    sendToClient(client, updateMessage);
                                    sendToClient(opponent, updateNextMessage);
                                }
                            } else {
                                // game finished
                                var next = game.getNext();
                                if (next == null) {
                                    var tieMessage = MessageManager
                                            .createGameEndTieMessage(0, null);  //fixme
                                    sendToClient(client, tieMessage);
                                    sendToClient(opponent, tieMessage);
                                } else {
                                    var victoryMessage = MessageManager
                                            .createGameEndVictoryMessage(0, null);  //fixme
                                    var defeatMessage = MessageManager
                                            .createGameEndDefeatMessage(0, null);  //fixme
                                    if(next.equals(color)) {
                                        sendToClient(client, victoryMessage);
                                        sendToClient(opponent, defeatMessage);
                                    } else {
                                        sendToClient(client, defeatMessage);
                                        sendToClient(opponent, victoryMessage);
                                    }
                                }
                            }
                            return null;
                        }, gameProtector);
                        return null;
                    }, client);
                }
                default: {
                    sendToClient(client, MessageManager.createErrorMessage("unknown message code "
                            + messageCode));
                }
            }
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
            try {
                //todo cache and try resending, recognize new client
                sendToClient(client, MessageManager.createErrorMessage(e.getMessage()));
            } catch (Exception ce) {
                logger.warn(ce.getMessage(), ce);
            }
        }
    }

    private void sendToClient(Client client, String message) {
        try {
            client.send(message);
        } catch (ClientException ce) {
            logger.warn(ce.getMessage());
        }
    }


}
