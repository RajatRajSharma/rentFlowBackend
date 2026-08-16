package com.rentflow.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Email, printed rather than sent. Real SMTP is a credential and a vendor away — everything
 * that makes emailing interesting here (who gets told, when, off which thread) is already real.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final int RETAINED = 200;

    /** The recent outbox: what a mail server would have received. Read by tests and by eye. */
    private final Deque<SentEmail> sent = new ArrayDeque<>();

    public void send(String to, String subject, String body) {
        log.info("""

                ---------------------------- EMAIL ----------------------------
                 to     : {}
                 subject: {}
                 {}
                ---------------------------------------------------------------""",
                to, subject, body);

        record(new SentEmail(to, subject, body));
    }

    public synchronized List<SentEmail> outbox() {
        return new ArrayList<>(sent);
    }

    public synchronized void clearOutbox() {
        sent.clear();
    }

    private synchronized void record(SentEmail email) {
        sent.addLast(email);
        if (sent.size() > RETAINED) {
            sent.removeFirst();
        }
    }

    public record SentEmail(String to, String subject, String body) {
    }
}
