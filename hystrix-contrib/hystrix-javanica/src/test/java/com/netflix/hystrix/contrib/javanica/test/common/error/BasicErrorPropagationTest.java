/**
 * Copyright 2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix.contrib.javanica.test.common.error;

import com.netflix.hystrix.HystrixEventType;
import com.netflix.hystrix.HystrixRequestLog;
import com.netflix.hystrix.contrib.javanica.annotation.HystrixCommand;
import com.netflix.hystrix.contrib.javanica.test.common.BasicHystrixTest;
import com.netflix.hystrix.contrib.javanica.test.common.domain.User;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static com.netflix.hystrix.contrib.javanica.test.common.CommonUtils.getHystrixCommandByKey;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Created by dmgcodevil
 */
public abstract class BasicErrorPropagationTest extends BasicHystrixTest {

    private static final String COMMAND_KEY = "getUserById";

    private static final Map<String, User> USERS;

    static {
        USERS = new HashMap<String, User>();
        USERS.put("1", new User("1", "user_1"));
        USERS.put("2", new User("2", "user_2"));
        USERS.put("3", new User("3", "user_3"));
    }

    private UserService userService;

    @MockitoAnnotations.Mock
    private FailoverService failoverService;

    protected abstract UserService createUserService();

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        userService = createUserService();
        userService.setFailoverService(failoverService);
    }

    @Test(expected = BadRequestException.class)
    public void testGetUserByBadId() throws NotFoundException {
        try {
            String badId = "";
            userService.getUserById(badId);
        } finally {
            assertEquals(1, HystrixRequestLog.getCurrentRequest().getAllExecutedCommands().size());
            com.netflix.hystrix.HystrixInvokableInfo getUserCommand = getHystrixCommandByKey(COMMAND_KEY);
            // will not affect metrics
            assertFalse(getUserCommand.getExecutionEvents().contains(HystrixEventType.FAILURE));
            // and will not trigger fallback logic
            verify(failoverService, never()).getDefUser();
        }
    }

    @Test(expected = NotFoundException.class)
    public void testGetNonExistentUser() throws NotFoundException {
        try {
            userService.getUserById("4"); // user with id 4 doesn't exist
        } finally {
            assertEquals(1, HystrixRequestLog.getCurrentRequest().getAllExecutedCommands().size());
            com.netflix.hystrix.HystrixInvokableInfo getUserCommand = getHystrixCommandByKey(COMMAND_KEY);
            // will not affect metrics
            assertFalse(getUserCommand.getExecutionEvents().contains(HystrixEventType.FAILURE));
            // and will not trigger fallback logic
            verify(failoverService, never()).getDefUser();
        }
    }

    @Test // don't expect any exceptions because fallback must be triggered
    public void testActivateUser() throws NotFoundException, ActivationException {
        try {
            userService.activateUser("1"); // this method always throws ActivationException
        } finally {
            assertEquals(1, HystrixRequestLog.getCurrentRequest().getAllExecutedCommands().size());
            com.netflix.hystrix.HystrixInvokableInfo activateUserCommand = getHystrixCommandByKey("activateUser");
            // will not affect metrics
            assertTrue(activateUserCommand.getExecutionEvents().contains(HystrixEventType.FAILURE));
            assertTrue(activateUserCommand.getExecutionEvents().contains(HystrixEventType.FALLBACK_SUCCESS));
            // and will not trigger fallback logic
            verify(failoverService, atLeastOnce()).activate();
        }
    }

    @Test(expected = HystrixRuntimeException.class)
    public void testBlockUser() throws NotFoundException, ActivationException, OperationException {
        try {
            userService.blockUser("1"); // this method always throws ActivationException
        } finally {
            assertEquals(2, HystrixRequestLog.getCurrentRequest().getAllExecutedCommands().size());
            com.netflix.hystrix.HystrixInvokableInfo activateUserCommand = getHystrixCommandByKey("blockUser");
            // will not affect metrics
            assertTrue(activateUserCommand.getExecutionEvents().contains(HystrixEventType.FAILURE));
            assertTrue(activateUserCommand.getExecutionEvents().contains(HystrixEventType.FALLBACK_FAILURE));
        }
    }

    public static class UserService {

        private FailoverService failoverService;

        public void setFailoverService(FailoverService failoverService) {
            this.failoverService = failoverService;
        }

        @HystrixCommand(
                commandKey = COMMAND_KEY,
                ignoreExceptions = {
                        BadRequestException.class,
                        NotFoundException.class
                },
                fallbackMethod = "fallback")
        public User getUserById(String id) throws NotFoundException {
            validate(id);
            if (!USERS.containsKey(id)) {
                throw new NotFoundException("user with id: " + id + " not found");
            }
            return USERS.get(id);
        }


        @HystrixCommand(
                ignoreExceptions = {BadRequestException.class, NotFoundException.class},
                fallbackMethod = "activateFallback")
        public void activateUser(String id) throws NotFoundException, ActivationException {
            validate(id);
            if (!USERS.containsKey(id)) {
                throw new NotFoundException("user with id: " + id + " not found");
            }
            // always throw this exception
            throw new ActivationException("user cannot be activate");
        }

        @HystrixCommand(
                ignoreExceptions = {BadRequestException.class, NotFoundException.class},
                fallbackMethod = "blockUserFallback")
        public void blockUser(String id) throws NotFoundException, OperationException {
            validate(id);
            if (!USERS.containsKey(id)) {
                throw new NotFoundException("user with id: " + id + " not found");
            }
            // always throw this exception
            throw new OperationException("user cannot be blocked");
        }

        private User fallback(String id) {
            return failoverService.getDefUser();
        }

        private void activateFallback(String id) {
            failoverService.activate();
        }

        @HystrixCommand(ignoreExceptions = {RuntimeException.class})
        private void blockUserFallback(String id) {
            throw new RuntimeOperationException("blockUserFallback has failed");
        }

        private void validate(String val) throws BadRequestException {
            if (val == null || val.length() == 0) {
                throw new BadRequestException("parameter cannot be null ot empty");
            }
        }
    }

    private class FailoverService {
        public User getDefUser() {
            return new User("def", "def");
        }

        public void activate() {
        }
    }

    // exceptions
    private static class NotFoundException extends Exception {
        private NotFoundException(String message) {
            super(message);
        }
    }

    private static class BadRequestException extends RuntimeException {
        private BadRequestException(String message) {
            super(message);
        }
    }

    private static class ActivationException extends Exception {
        private ActivationException(String message) {
            super(message);
        }
    }

    private static class OperationException extends Throwable {
        private OperationException(String message) {
            super(message);
        }
    }

    private static class RuntimeOperationException extends RuntimeException {
        private RuntimeOperationException(String message) {
            super(message);
        }
    }

}
