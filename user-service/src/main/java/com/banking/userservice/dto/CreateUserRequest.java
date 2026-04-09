package com.banking.userservice.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/*
 * Java 21 record: the compiler generates a canonical constructor, accessors
 * (firstName(), not getFirstName()), equals, hashCode, and toString.
 * Bean Validation annotations on record components are picked up by
 * Hibernate Validator 8 because the annotation target includes FIELD,
 * which records apply to the backing field.
 */
public record CreateUserRequest(

        @NotBlank(message = "First name is required")
        String firstName,

        @NotBlank(message = "Last name is required")
        String lastName,

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        String email
) {}
