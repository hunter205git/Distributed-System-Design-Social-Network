package com.socket.server.dao;

import com.socket.server.entity.AppUser;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class UserDao {

    MongoDBManager dbManager;

    public UserDao() {
        this.dbManager = new MongoDBManager();
    }

    private List<String> fetchUnreadMessage(String username) {
        return dbManager.getUnreadMsg(username);
    }

    public void updateLastActiveTime(String username) {
        dbManager.updateUserLastActiveTime(username, new Date());
    }

    public AppUser validate(String username, String password) {
        if (isValid(username, password)) {
            return getUser(username, password);
        } else {
            return null;
        }
    }

    private boolean isValid(String userName, String passWord) {
        if (dbManager == null) return false;
        return dbManager.isValidUser(userName, passWord);
    }


    private AppUser getUser(String username, String password) {
        AppUser user = new AppUser(username, password);
        user.setUnreadMessages(fetchUnreadMessage(username));
        return user;
    }
}
