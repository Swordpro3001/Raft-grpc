package com.example.raftimplementation;

import com.example.raftimplementation.service.RaftNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
	"spring.profiles.active=node",
	"raft.node-id=test",
	"raft.grpc-port=0",
	"spring.datasource.url=jdbc:h2:mem:context-test"
})
class RaftimplementationApplicationTests {

	@MockitoBean
	private RaftNode raftNode;

	@Test
	void contextLoads() {
	}

}
