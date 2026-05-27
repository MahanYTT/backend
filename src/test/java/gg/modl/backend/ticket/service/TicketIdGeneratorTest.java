package gg.modl.backend.ticket.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gg.modl.backend.database.mongo.repository.TicketMongoRepository;
import gg.modl.backend.infrastructure.util.IdGenerator;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.server.data.ServerPlan;
import gg.modl.backend.ticket.data.TicketCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketIdGeneratorTest {

    @Test
    void generatePreservesPrefixedSixDigitPublicIdShape() {
        TicketMongoRepository ticketRepository = mock(TicketMongoRepository.class);
        Server server = new Server("server", "domain", "db", "admin@example.com", true, ServerPlan.FREE);
        when(ticketRepository.existsByTicketId(server, "SUPPORT-100000")).thenReturn(false);

        TicketIdGenerator generator = new TicketIdGenerator(new FixedIdGenerator(), ticketRepository);

        String id = generator.generate(server, TicketCategory.SUPPORT);

        assertTrue(id.matches("SUPPORT-\\d{6}"));
    }

    private static class FixedIdGenerator extends IdGenerator {
        @Override
        public int nextSixDigitInt() {
            return 100000;
        }
    }
}
