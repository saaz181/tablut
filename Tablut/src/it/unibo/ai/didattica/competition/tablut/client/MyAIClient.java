package it.unibo.ai.didattica.competition.tablut.client;

import it.unibo.ai.didattica.competition.tablut.domain.Action;
import it.unibo.ai.didattica.competition.tablut.domain.Game;
import it.unibo.ai.didattica.competition.tablut.domain.GameAshtonTablut;
import it.unibo.ai.didattica.competition.tablut.domain.State;
import it.unibo.ai.didattica.competition.tablut.domain.State.Pawn;
import it.unibo.ai.didattica.competition.tablut.domain.State.Turn;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;


public class MyAIClient extends TablutClient {

    private Game gameRules;
    private int maxDepth = 3;

    public MyAIClient(String player, String name, int timeout, String ip) throws UnknownHostException, IOException {
        
        super(player, name, timeout, ip);
        this.gameRules = new GameAshtonTablut(0, 0, "logs", "AI_Internal", "AI_Internal");


    }

    public static void main(String[] args) throws UnknownHostException, IOException {
        String role = "WHITE";
        String name = "MyAIPlayer";
        int timeout = 60;
        String ip = "localhost";

        
        if (args.length > 0) role = args[0].toUpperCase();
        if (args.length > 1) timeout = Integer.parseInt(args[1]);
        if (args.length > 2) ip = args[2];
        if (args.length > 3) name = args[3];

        MyAIClient client = new MyAIClient(role, name, timeout, ip);
        client.run();

        
    }

    
    @Override
public void run() {
    try {
        this.declareName();
        System.out.println("AI Connected. My role is: " + this.getPlayer().toString());
    } catch (Exception e) {
        e.printStackTrace();
        return;
    }

    while (true) {
        try {
            try {
                this.read(); 
            } catch (ClassNotFoundException | IOException readEx) {
                System.err.println("Error while reading from server: " + readEx.getMessage());
                readEx.printStackTrace();
                break;
            }

            State currentState = this.getCurrentState();

            if (currentState == null) {
                System.err.println("received null state from server. Waiting for server to send initial state...");
                try { Thread.sleep(500); } catch (InterruptedException e) { /* ignore */ }
                continue;
            }

            Turn currentTurn = currentState.getTurn();

            // Check if the game is over
            if (currentTurn == Turn.WHITEWIN || currentTurn == Turn.BLACKWIN || currentTurn == Turn.DRAW) {
                System.out.println("Game Over. Result: " + currentTurn);
                break;
            }

            // Only "think" if it's our turn
            if (currentTurn == this.getPlayer()) {
                System.out.println("\nIt's my turn (" + this.getPlayer() + "). Thinking...");
                long startTime = System.currentTimeMillis();

                Action bestAction = findBestMove(currentState);

                long endTime = System.currentTimeMillis();

                if (bestAction == null) {
                    System.out.println("No valid move found by the AI. Passing / waiting (or resigning)...");
                    break;
                } else {
                    System.out.println("AI chose: " + bestAction + " in " + (endTime - startTime) + "ms");
                    try {
                        this.write(bestAction);
                    } catch (Exception writeEx) {
                        System.err.println("Error writing action to server: " + writeEx.getMessage());
                        writeEx.printStackTrace();
                        break;
                    }
                }
            } else {
               
            }

        } catch (Exception e) {
            System.out.println("An error occurred: " + e.getMessage());
            e.printStackTrace();
            break;
        }
    }
}

private Action findBestMove(State state) {
        
    List<Action> possibleActions = generatePossibleActions(state);

    if (possibleActions.isEmpty()) {
        System.err.println("⚠️ AI has no possible moves.");
        return null;
    }

    Action bestAction = null;
    int bestValue = Integer.MIN_VALUE; 

    int alpha = Integer.MIN_VALUE;
    int beta = Integer.MAX_VALUE;

    System.out.println("AI is considering " + possibleActions.size() + " possible moves...");

    for (Action action : possibleActions) {
        
        State newState = applyAction(state.clone(), action);
        
        int moveValue = minimax(newState, 0, alpha, beta, false); 

        if (moveValue > bestValue) {
            bestValue = moveValue;
            bestAction = action;
        }

        alpha = Math.max(alpha, bestValue);
    }

    if (bestAction == null) {
        System.out.println("No clearly 'best' move found, picking first available.");
        return possibleActions.get(0);
    }

    return bestAction;
}
     

    private int minimax(State state, int depth, int alpha, int beta, boolean isMaximizingPlayer) {
        Turn winner = state.getTurn();

        if (depth == this.maxDepth || winner == Turn.WHITEWIN || winner == Turn.BLACKWIN || winner == Turn.DRAW) {
            return evaluate(state);
        }

        List<Action> possibleActions = generatePossibleActions(state);
        
        if (possibleActions.isEmpty()) {
            return evaluate(state); // No moves, terminal state
        }

        if (isMaximizingPlayer) {
            int maxEval = Integer.MIN_VALUE;
            for (Action action : possibleActions) {
                State newState = applyAction(state.clone(), action);
                int eval = minimax(newState, depth + 1, alpha, beta, false);
                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);
                if (beta <= alpha) break;
            }
            return maxEval;
        } else { 
            // Minimizing Player
            int minEval = Integer.MAX_VALUE;
            for (Action action : possibleActions) {
                State newState = applyAction(state.clone(), action);
                int eval = minimax(newState, depth + 1, alpha, beta, true);
                minEval = Math.min(minEval, eval);
                beta = Math.min(beta, eval);
                if (beta <= alpha) break;
            }
            return minEval;
        }
    }

   
    private State applyAction(State state, Action action) {
        try {
            // checkMove validates, applies the move, checks for captures, and returns the new state
            return this.gameRules.checkMove(state, action);
        } catch (Exception e) {
            e.printStackTrace();
            return state;
        }
    }
    
    
    private List<Action> generatePossibleActions(State state) {
        List<Action> validActions = new ArrayList<>();
        Turn player = state.getTurn();
        Pawn[][] board = state.getBoard();

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                State.Pawn currentPawn = board[r][c];

                // Check if the pawn belongs to the current player
                boolean isMyPawn = false;
                if (player.equals(Turn.WHITE) && (currentPawn.equals(Pawn.WHITE) || currentPawn.equals(Pawn.KING))) {
                    isMyPawn = true;
                } else if (player.equals(Turn.BLACK) && currentPawn.equals(Pawn.BLACK)) {
                    isMyPawn = true;
                }

                if (isMyPawn) {
                    String from = state.getBox(r, c);

                    // Check moves UP (r-1)
                    for (int rowTo = r - 1; rowTo >= 0; rowTo--) {
                        if (board[rowTo][c] != Pawn.EMPTY) break; // Path blocked
                        tryAddAction(state, validActions, from, state.getBox(rowTo, c), player);
                    }
                    
                    // Check moves DOWN (r+1)
                    for (int rowTo = r + 1; rowTo < 9; rowTo++) {
                        if (board[rowTo][c] != Pawn.EMPTY) break; // Path blocked
                        tryAddAction(state, validActions, from, state.getBox(rowTo, c), player);
                    }
                    
                    // Check moves LEFT (c-1)
                    for (int colTo = c - 1; colTo >= 0; colTo--) {
                        if (board[r][colTo] != Pawn.EMPTY) break; // Path blocked
                        tryAddAction(state, validActions, from, state.getBox(r, colTo), player);
                    }
                    
                    // Check moves RIGHT (c+1)
                    for (int colTo = c + 1; colTo < 9; colTo++) {
                        if (board[r][colTo] != Pawn.EMPTY) break; // Path blocked
                        tryAddAction(state, validActions, from, state.getBox(r, colTo), player);
                    }
                }
            }
        }
        return validActions;
    }

    private void tryAddAction(State state, List<Action> validActions, String from, String to, Turn player) {
        try {
            Action action = new Action(from, to, player);
            this.gameRules.checkMove(state.clone(), action); 
            validActions.add(action);
        } catch (Exception e) {
        }
    }

    
    private int evaluate(State state) {
        Turn winner = state.getTurn();
        Turn myPlayer = this.getPlayer();

        if (winner == Turn.WHITEWIN) {
            return (myPlayer == Turn.WHITE) ? 100000 : -100000;
        }
        if (winner == Turn.BLACKWIN) {
            return (myPlayer == Turn.BLACK) ? 100000 : -100000;
        }
        if (winner == Turn.DRAW) {
            return 0;
        }

        int score = 0;
        Pawn[][] board = state.getBoard();
        int whitePawns = 0;
        int blackPawns = 0;
        int[] kingPos = new int[]{-1, -1};

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                if (board[r][c].equals(Pawn.WHITE)) whitePawns++;
                else if (board[r][c].equals(Pawn.BLACK)) blackPawns++;
                else if (board[r][c].equals(Pawn.KING)) kingPos = new int[]{r, c};
            }
        }

        int pieceScore = (whitePawns * 10) - (blackPawns * 12);
        score += (myPlayer == Turn.WHITE) ? pieceScore : -pieceScore;


        if (kingPos[0] != -1) { // If King is on the board
            int rk = kingPos[0];
            int ck = kingPos[1];

            if (myPlayer == Turn.WHITE) {
                int clearPaths = 0;
                
                boolean pathUpBlocked = false;
                for(int r = rk - 1; r >= 0; r--) {
                    if(!board[r][ck].equals(Pawn.EMPTY)) {
                        pathUpBlocked = true;
                        break;
                    }
                }
                if (!pathUpBlocked && (ck == 1 || ck == 2 || ck == 6 || ck == 7)) clearPaths++; // (0,1),(0,2),(0,6),(0,7)
                
                // Check path DOWN
                boolean pathDownBlocked = false;
                for(int r = rk + 1; r < 9; r++) {
                    if(!board[r][ck].equals(Pawn.EMPTY)) {
                        pathDownBlocked = true;
                        break;
                    }
                }
                if (!pathDownBlocked && (ck == 1 || ck == 2 || ck == 6 || ck == 7)) clearPaths++; // (8,1),(8,2),(8,6),(8,7)

                
                score += clearPaths * 50; // Big bonus for each open escape route

            } else { 
                // AI is BLACK
                // --- BLACK'S GOAL: TRAP THE KING ---
                
                // New Heuristic: Count "traps" adjacent to the king
                
                int adjacentTraps = 0;
                
                // Check UP
                if (rk == 0 || board[rk - 1][ck].equals(Pawn.BLACK) || isCitadel(rk - 1, ck)) {
                    adjacentTraps++;
                }
                // Check DOWN
                if (rk == 8 || board[rk + 1][ck].equals(Pawn.BLACK) || isCitadel(rk + 1, ck)) {
                    adjacentTraps++;
                }
                // Check LEFT
                if (ck == 0 || board[rk][ck - 1].equals(Pawn.BLACK) || isCitadel(rk, ck - 1)) {
                    adjacentTraps++;
                }
                // Check RIGHT
                if (ck == 8 || board[rk][ck + 1].equals(Pawn.BLACK) || isCitadel(rk, ck + 1)) {
                    adjacentTraps++;
                }
                
                // This score scales quadratically: 1 trap=10, 2 traps=40, 3 traps=90
                score += adjacentTraps * adjacentTraps * 10; 
            }
        }

        return score;
    }

    /**
     * Helper function to check if a square is a citadel (or the throne).
     * These squares act as "allies" for Black when trapping the king.
     */
    private boolean isCitadel(int r, int c) {
        if (r == 4 && c == 4) return true; // Throne
        if (r == 0 && (c == 3 || c == 4 || c == 5)) return true;
        if (r == 1 && c == 4) return true;
        if (r == 3 && (c == 0 || c == 8)) return true;
        if (r == 4 && (c == 0 || c == 1 || c == 7 || c == 8)) return true;
        if (r == 5 && (c == 0 || c == 8)) return true;
        if (r == 7 && c == 4) return true;
        if (r == 8 && (c == 3 || c == 4 || c == 5)) return true;
        return false;
    }
}