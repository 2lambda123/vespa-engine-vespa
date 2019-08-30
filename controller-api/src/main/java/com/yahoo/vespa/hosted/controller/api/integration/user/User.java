package com.yahoo.vespa.hosted.controller.api.integration.user;

import java.util.Objects;

public class User {

    private final String name;
    private final String email;
    private final String nickname;
    private final String picture;

    public User(String email, String name, String nickname, String picture) {
        Objects.requireNonNull(email);
        Objects.requireNonNull(name);

        this.name = name;
        this.email = email;
        this.nickname = nickname;
        this.picture = picture;
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public String nickname() {
        return nickname;
    }

    public String picture() {
        return picture;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(name, user.name) &&
                Objects.equals(email, user.email) &&
                Objects.equals(nickname, user.nickname) &&
                Objects.equals(picture, user.picture);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, email, nickname, picture);
    }
}
