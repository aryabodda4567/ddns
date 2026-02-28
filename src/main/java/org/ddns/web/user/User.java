package org.ddns.web.user;

import org.ddns.db.DBUtil;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

public class User {
    private String username;
    private String password;
    private String email;
    private String firstName;
    private String lastName;

    public User(String username, String password, String email, String firstName, String lastName) {
        this.username = username;
        this.password = password;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    @Override
    public String toString() {
        return "User{" +
                "username='" + username + '\'' +
                ", password='" + password + '\'' +
                ", email='" + email + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                '}';
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public static void saveUser(User user) {
        if (user == null || user.getUsername() == null || user.getPassword() == null) {
            throw new IllegalArgumentException("User credentials are required");
        }

        DBUtil db = DBUtil.getInstance();

        db.putString(ConfigKey.USERNAME.key(), user.getUsername());
        db.putString(ConfigKey.PASSWORD.key(), hashString(user.getPassword()));
        db.putString(ConfigKey.EMAIL.key(), user.getEmail());
        db.putString(ConfigKey.FIRSTNAME.key(), user.getFirstName());
        db.putString(ConfigKey.LASTNAME.key(), user.getLastName());

        db.putInt(ConfigKey.IS_LOGGED_IN.key(), 0);
    }

    public static User getUser() {
        DBUtil db = DBUtil.getInstance();

        String username = db.getString(ConfigKey.USERNAME.key());
        String password = db.getString(ConfigKey.PASSWORD.key());
        String email = db.getString(ConfigKey.EMAIL.key());
        String firstName = db.getString(ConfigKey.FIRSTNAME.key());
        String lastName = db.getString(ConfigKey.LASTNAME.key());

        if (username == null) {
            return null;
        }

        return new User(username, password, email, firstName, lastName);
    }

    public static User fromCredentials(String username, String password) {
        if (username == null || password == null) {
            throw new IllegalArgumentException("Username and password are required");
        }

        return new User(username.trim(), password.trim(), "", "", "");
    }

    private static String hashString(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes());

            StringBuilder hexString = new StringBuilder();

            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Error hashing string", e);
        }
    }

    public boolean checkPassword(String candidatePassword) {
        if (candidatePassword == null || getPassword() == null) {
            return false;
        }
        String stored = getPassword().trim();
        String candidate = candidatePassword.trim();
        String candidateHash = hashString(candidate);

        // Backward compatibility:
        // - current format: SHA-256 hash in DB
        // - legacy/dirty data: plain-text password accidentally persisted
        return Objects.equals(stored, candidateHash) || Objects.equals(stored, candidate);
    }

    public boolean checkUsername(String candidateUsername) {
        if (candidateUsername == null || getUsername() == null) {
            return false;
        }
        return getUsername().trim().equalsIgnoreCase(candidateUsername.trim());
    }

    public boolean login(String username, String password) {
        return checkUsername(username) && checkPassword(password);
    }

    public static boolean verifyCredentials(String username, String password) {
        User storedUser = getUser();
        if (storedUser == null) {
            return false;
        }

        return storedUser.login(username, password);
    }
}
