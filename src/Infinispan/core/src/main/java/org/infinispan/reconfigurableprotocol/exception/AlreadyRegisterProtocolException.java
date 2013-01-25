package org.infinispan.reconfigurableprotocol.exception;

import org.infinispan.reconfigurableprotocol.ReconfigurableProtocol;

/**
 * Exception that is thrown when it tries to register a already register protocol
 *
 * @author Pedro Ruivo
 * @since 5.2
 */
public class AlreadyRegisterProtocolException extends Exception {

   public AlreadyRegisterProtocolException() {
   }

   public AlreadyRegisterProtocolException(String s) {
      super(s);
   }

   public AlreadyRegisterProtocolException(String s, Throwable throwable) {
      super(s, throwable);
   }

   public AlreadyRegisterProtocolException(Throwable throwable) {
      super(throwable);
   }

   public AlreadyRegisterProtocolException(ReconfigurableProtocol protocol) {
      super("The protocol " + protocol + " is already register");
   }
}
