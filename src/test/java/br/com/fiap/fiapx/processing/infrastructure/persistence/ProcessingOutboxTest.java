package br.com.fiap.fiapx.processing.infrastructure.persistence;

import br.com.fiap.fiapx.processing.infrastructure.persistence.adapter.ProcessingOutbox;
import java.sql.ResultSet;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProcessingOutboxTest {
    @Test void commitBoundaryPrecedesReturningClaimAndUncertaintyPropagates() throws Exception {
        var jdbc=mock(JdbcTemplate.class); var manager=mock(PlatformTransactionManager.class);
        var status=mock(TransactionStatus.class); when(manager.getTransaction(any())).thenReturn(status);
        var adapter=new ProcessingOutbox(jdbc,new TransactionTemplate(manager));
        var id=UUID.randomUUID(); var token=UUID.randomUUID();
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(call->{
            var rs=mock(ResultSet.class); when(rs.getObject(1,UUID.class)).thenReturn(id);
            when(rs.getObject(2,UUID.class)).thenReturn(token); when(rs.getString(3)).thenReturn("body");
            return List.of(((RowMapper<?>)call.getArgument(1)).mapRow(rs,0));
        });
        var event=adapter.claim().orElseThrow(); assertEquals(new ProcessingOutbox.Publication(id,token,"body"),event);
        verify(manager).commit(status); adapter.published(event); adapter.retry(event);
        verify(jdbc,times(2)).update(anyString(),eq(id),eq(token));
        doThrow(new IllegalStateException("commit uncertain")).when(manager).commit(status);
        assertThrows(IllegalStateException.class,adapter::claim);
    }
}
