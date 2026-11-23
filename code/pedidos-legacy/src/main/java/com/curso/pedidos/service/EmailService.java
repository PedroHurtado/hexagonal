package com.curso.pedidos.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    @Autowired private JavaMailSender mailSender;

    public void enviar(String to, String asunto, String html) throws MessagingException {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
        h.setTo(to);
        h.setSubject(asunto);
        h.setText(html, true);
        mailSender.send(msg);
    }
}
