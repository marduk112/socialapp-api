package com.socialapp.usermanagement.application.usecase;

import com.socialapp.usermanagement.application.command.CreateUserCommand;
import com.socialapp.usermanagement.domain.User;

public interface CreateUserUseCase {
    User handle(CreateUserCommand command);
}
