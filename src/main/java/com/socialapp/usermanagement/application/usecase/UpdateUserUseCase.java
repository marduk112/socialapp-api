package com.socialapp.usermanagement.application.usecase;

import com.socialapp.usermanagement.application.command.UpdateUserCommand;
import com.socialapp.usermanagement.domain.User;

public interface UpdateUserUseCase {
    User handle(UpdateUserCommand command);
}
