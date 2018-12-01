package com.socket.server.thread;

import com.socket.server.ServerApp;
import com.socket.server.entity.AppUser;
import com.socket.server.entity.NotificationMessage;
import com.socket.totp.TotpCmd;
import com.socket.totp.TotpReqHeaderField;
import com.socket.totp.TotpServer;
import com.socket.totp.TotpStatus;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;

public class SlaveThread extends Thread {

    private final ServerApp server;
    private Socket clientSocket;
    private Socket notiSocket;
    private TotpServer serverTotp;
    private DataInputStream dis = null;
    private DataOutputStream dos = null;
    private AppUser user = null;
    private volatile OnlineStatus onlineStatus = OnlineStatus.OFFLINE;
    private volatile ReceivingDataStatus receivingDataStatus = ReceivingDataStatus.OFF;

    public SlaveThread(ServerApp server, Socket clientSocket, Socket notiSocket) {
        this.clientSocket = clientSocket;
        this.notiSocket = notiSocket;
        this.server = server;
        this.serverTotp = new TotpServer(clientSocket);
    }

    @Override
    public void run() {
        try {

            while(true) {
                // Block for incoming requests from client
                Map<TotpReqHeaderField, String> req = serverTotp.receiveReq();

                String reqToken = req.get(TotpReqHeaderField.TOKEN_ID);
                String reqCommand = req.get(TotpReqHeaderField.COMMAND);

                // Check empty command
                if(reqCommand == null || reqCommand.equals("")) {
                    serverTotp.respond(TotpCmd.ERROR, TotpStatus.ERROR_PARAMETERS_ARGUMENTS);
                    continue;
                }
                // Check if token exists
                if(reqToken == null || reqToken.equals("")) {
                    // No token, ok for PASS and HELO, error for all other commands
                    if(reqCommand.equals(TotpCmd.HELO.toString())) {
                        serverTotp.respond(TotpCmd.HELO, TotpStatus.AUTHENTICATION_REQUIRED);
                        continue;
                    }
                    else if(reqCommand.equals(TotpCmd.PASS.toString())) {
                        String username = req.get(TotpReqHeaderField.USER);
                        String password = req.get(TotpReqHeaderField.PASSWORD);
                        user = server.userLogin(username, password);

                        if(user != null) {
                            // Successfully login
                            serverTotp.respond(TotpCmd.PASS, TotpStatus.SUCCESS, user.getToken());
                            // Update thread to be online
                            setOnlineStatus(OnlineStatus.ONLINE);
                            sendMessage(user.getToken(), String.format("Welcome %s, you have %d unread messages.",
                                    user.getUsername(), user.getUnreadMessages().size()));
                            for (String unread : user.getUnreadMessages()) {
                                sendMessage(user.getToken(), "[Unread] " + unread);
                            }

                            sendMessage(NotificationMessage.EMPTY_TOKEN,
                                    String.format("User %s has been online now.", user.getUsername()));
                        }
                        else {
                            // Login validation failed
                            serverTotp.respond(TotpCmd.PASS, TotpStatus.PERMISSION_FAILED);
                        }
                        continue;
                    }
                    else {
                        serverTotp.respond(TotpCmd.ERROR, TotpStatus.PERMISSION_FAILED);
                        continue;
                    }
                }

                // Validate token
                if(!server.validateToken(reqToken)) {
                    serverTotp.respond(TotpCmd.valueOf(reqCommand), TotpStatus.AUTHENTICATION_REQUIRED);
                    continue;
                }

                // Handle other commands
                if(reqCommand.equals(TotpCmd.GBYE.toString())) {
                    setOnlineStatus(OnlineStatus.OFFLINE);
                    System.out.println("Closing this connection.");
                    this.shutdown();
                    System.out.println("Connection closed");
                    break;
                }

                if(reqCommand.equals(TotpCmd.SEND.toString())) {
                    this.receivingDataStatus = ReceivingDataStatus.RECEIVING;
                    serverTotp.respond(TotpCmd.SEND, TotpStatus.READY_LIST_RECEIVING);
                    continue;
                }

                if(reqCommand.equals(TotpCmd.DATA.toString())) {
                    if(!this.receivingDataStatus.equals(ReceivingDataStatus.RECEIVING)) {
                        serverTotp.respond(TotpCmd.DATA, TotpStatus.TRANSMISSION_FAILED);
                    }
                    // Receive message and broadcast notification
                    sendMessage(NotificationMessage.EMPTY_TOKEN, String.format("%s posted: %s",
                            user.getUsername(), req.get(TotpReqHeaderField.MESSAGE)));

                    serverTotp.respond(TotpCmd.DATA, TotpStatus.TRANSFER_ACTION_COMPLETED);
                    continue;
                }
            }


        } catch (EOFException e) {
            // Client became offline, close socket and IO streams
            this.shutdown();
            System.out.println(String.format("%s closed", user.getUsername()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void shutdown() {
        try {
            serverTotp.close();
            clientSocket.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendMessage(String token, String message) {
        server.addMessage(new NotificationMessage(token, message));
    }

    public void setOnlineStatus(OnlineStatus onlineStatus) {
        this.onlineStatus = onlineStatus;

        if (onlineStatus == OnlineStatus.ONLINE) {
            server.addOnlineUser(user.getToken(), this, this.notiSocket);
        } else {
            server.removeOnlineUser(user.getToken());

            try {
                dos.writeUTF("exit");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void send(String message) throws IOException {
        dos.writeUTF(message);
    }

    enum OnlineStatus {
        ONLINE, OFFLINE
    }

    enum ReceivingDataStatus {
        RECEIVING, OFF
    }
}