CREATE TABLE roles (
    id varchar(255) NOT NULL,
    description varchar(255),
    name varchar(255) NOT NULL,
    CONSTRAINT roles_pkey PRIMARY KEY (id),
    CONSTRAINT uk_roles_name UNIQUE (name)
);

CREATE TABLE users (
    id varchar(255) NOT NULL,
    active boolean DEFAULT true,
    contact_number varchar(255),
    created_at timestamp(6) with time zone,
    email varchar(255) NOT NULL,
    first_name varchar(255),
    last_login_at timestamp(6) with time zone,
    last_name varchar(255),
    middle_name varchar(255),
    password varchar(255) NOT NULL,
    updated_at timestamp(6) with time zone,
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE user_roles (
    user_id varchar(255) NOT NULL,
    role_id varchar(255) NOT NULL,
    CONSTRAINT user_roles_pkey PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id)
);
