package ch.zxseitz.tbsg.model;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.security.core.GrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Document(collection = "users")
public class User {
    @Id
    public final ObjectId id;

    private final String username;
    private final String email;
    private final String password;  // hashed
    private final List<GrantedAuthority> roles;

    public User(ObjectId id, String username, String email, String password, Collection<GrantedAuthority> roles) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.password = password;
        this.roles = new ArrayList<>(roles);
    }

    public Collection<GrantedAuthority> getRoles() {
        return roles;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return password;
    }
}
