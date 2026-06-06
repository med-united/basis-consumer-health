package de.servicehealtherx.crypto.services.jpa;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "APP_CONFIG_PROPERTY")
public class AppConfigProperty extends PanacheEntityBase {

    @Id
    @Column(name = "PROPNAME", nullable = false, length = 256)
    public String propName;

    @Column(name = "PROPVALUE", nullable = false, length = 4096)
    public String propValue;

    public static AppConfigProperty findByName(String name) {
        return findById(name);
    }
}
