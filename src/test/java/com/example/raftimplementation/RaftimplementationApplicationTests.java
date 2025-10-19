package com.example.raftimplementation;

import com.example.raftimplementation.service.RaftNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class RaftimplementationApplicationTests {

	@MockBean
	private RaftNode raftNode;

	@Test
	void contextLoads() {
		// Test that Spring context loads successfully
		// RaftNode is mocked to avoid connection attempts to peers
	}

}
