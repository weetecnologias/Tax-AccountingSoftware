package com.taxpilot.users;

import com.taxpilot.common.ApiException;

/** Minimum 12 characters with at least one upper-case letter, one lower-case letter and one digit. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    public static void check(String field, String password) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH
                || !password.chars().anyMatch(Character::isUpperCase)
                || !password.chars().anyMatch(Character::isLowerCase)
                || !password.chars().anyMatch(Character::isDigit)) {
            throw ApiException.field(field, "Password must be 12-128 characters and include upper-case, "
                    + "lower-case and a digit");
        }
    }
}
