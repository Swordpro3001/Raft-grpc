package com.example.raftimplementation;

import com.example.raftimplementation.service.RaftNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class RaftimplementationApplicationTests {

	@MockitoBean
	private RaftNode raftNode;

	@Test
	void contextLoads() {
	}

}
