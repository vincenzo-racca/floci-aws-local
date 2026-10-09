package com.vincenzoracca.flociawslocal.model;


import java.util.Objects;
import java.util.UUID;

public abstract class PersonEntity {

    private UUID id;
    private String name;
    private String lastName;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PersonEntity entity)) return false;
        return Objects.equals(id, entity.id) && Objects.equals(name, entity.name) && Objects.equals(lastName, entity.lastName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, lastName);
    }

    @Override
    public String toString() {
        return "PersonEntity{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", lastName='" + lastName + '\'' +
                '}';
    }
}
