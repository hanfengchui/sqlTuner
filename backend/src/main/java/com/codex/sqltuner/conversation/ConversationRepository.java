package com.codex.sqltuner.conversation;

import com.codex.sqltuner.common.NotFoundException;
import com.codex.sqltuner.storage.PersistentAppState;
import com.codex.sqltuner.storage.PersistentStateStore;
import com.codex.sqltuner.tuning.SqlTuningTask;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Repository
public class ConversationRepository {
    private final PersistentStateStore stateStore;

    public ConversationRepository(PersistentStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public Conversation create(final Long userId, String title) {
        final String safeTitle = title == null || title.trim().isEmpty() ? "新建调优" : title.trim();
        // mutate 整段临界区：自增序列 + 插入会话/消息列表 + 落盘，原子完成，避免并发丢更新。
        return stateStore.mutate(new Function<PersistentAppState, Conversation>() {
            @Override
            public Conversation apply(PersistentAppState state) {
                LocalDateTime now = LocalDateTime.now();
                long id = state.getConversationSequence() + 1;
                state.setConversationSequence(id);
                Conversation conversation = new Conversation(id, userId, safeTitle, now, now);
                state.getConversations().put(id, conversation);
                state.getMessages().put(id, new ArrayList<Message>());
                return conversation;
            }
        });
    }

    public List<Conversation> listByUser(final Long userId) {
        return stateStore.read(new Function<PersistentAppState, List<Conversation>>() {
            @Override
            public List<Conversation> apply(PersistentAppState state) {
                List<Conversation> result = new ArrayList<Conversation>();
                for (Conversation conversation : state.getConversations().values()) {
                    if (conversation.getUserId().equals(userId)) {
                        // 返回副本，调用方排序/迭代不会触及共享 map。
                        result.add(copy(conversation));
                    }
                }
                Collections.sort(result, new Comparator<Conversation>() {
                    @Override
                    public int compare(Conversation left, Conversation right) {
                        return right.getUpdatedAt().compareTo(left.getUpdatedAt());
                    }
                });
                return result;
            }
        });
    }

    public Conversation getForUser(final Long id, final Long userId) {
        Conversation conversation = stateStore.read(new Function<PersistentAppState, Conversation>() {
            @Override
            public Conversation apply(PersistentAppState state) {
                return state.getConversations().get(id);
            }
        });
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new NotFoundException("会话不存在");
        }
        return copy(conversation);
    }

    public Message addMessage(final Long conversationId, final MessageRole role, final String content, final Long taskId) {
        return stateStore.mutate(new Function<PersistentAppState, Message>() {
            @Override
            public Message apply(PersistentAppState state) {
                Conversation conversation = state.getConversations().get(conversationId);
                if (conversation == null) {
                    throw new NotFoundException("会话不存在");
                }
                long id = state.getMessageSequence() + 1;
                state.setMessageSequence(id);
                Message message = new Message(id, conversationId, role, content, taskId, LocalDateTime.now());
                List<Message> list = state.getMessages().get(conversationId);
                if (list == null) {
                    list = new ArrayList<Message>();
                    state.getMessages().put(conversationId, list);
                }
                list.add(message);
                conversation.setUpdatedAt(message.getCreatedAt());
                if (role == MessageRole.USER && (conversation.getTitle() == null || "新建调优".equals(conversation.getTitle()))) {
                    conversation.setTitle(makeTitle(content));
                }
                return copy(message);
            }
        });
    }

    public List<Message> listMessages(final Long conversationId) {
        List<Message> list = stateStore.read(new Function<PersistentAppState, List<Message>>() {
            @Override
            public List<Message> apply(PersistentAppState state) {
                return state.getMessages().get(conversationId);
            }
        });
        if (list == null) {
            throw new NotFoundException("会话不存在");
        }
        // 返回副本列表，避免调用方迭代时被并发修改。
        List<Message> copy = new ArrayList<Message>(list.size());
        for (Message message : list) {
            copy.add(copy(message));
        }
        return copy;
    }

    public void deleteForUser(final Long id, final Long userId) {
        stateStore.mutate(new Function<PersistentAppState, Void>() {
            @Override
            public Void apply(PersistentAppState state) {
                Conversation conversation = state.getConversations().get(id);
                if (conversation == null || !conversation.getUserId().equals(userId)) {
                    throw new NotFoundException("会话不存在");
                }
                state.getConversations().remove(id);
                state.getMessages().remove(id);
                // 同步清理该会话下的孤儿任务，避免 restore/检索时残留引用。
                Iterator<Map.Entry<Long, SqlTuningTask>> iterator = state.getTasks().entrySet().iterator();
                while (iterator.hasNext()) {
                    SqlTuningTask task = iterator.next().getValue();
                    if (id.equals(task.getConversationId()) && userId.equals(task.getUserId())) {
                        iterator.remove();
                    }
                }
                return null;
            }
        });
    }

    /**
     * 仅用于测试/恢复场景：用外部提供的数据重建会话与消息。
     * 生产路径不调用。同时清理孤儿任务，避免脏数据。
     */
    public void restore(final List<Conversation> conversations, final List<Message> messages) {
        stateStore.mutate(new Function<PersistentAppState, Void>() {
            @Override
            public Void apply(PersistentAppState state) {
                state.getConversations().clear();
                state.getMessages().clear();
                if (conversations != null) {
                    for (Conversation conversation : conversations) {
                        state.getConversations().put(conversation.getId(), conversation);
                    }
                }
                if (messages != null) {
                    for (Message message : messages) {
                        List<Message> list = state.getMessages().get(message.getConversationId());
                        if (list == null) {
                            list = new ArrayList<Message>();
                            state.getMessages().put(message.getConversationId(), list);
                        }
                        list.add(message);
                    }
                }
                // 清理指向已不存在会话的孤儿任务。
                Iterator<Map.Entry<Long, SqlTuningTask>> iterator = state.getTasks().entrySet().iterator();
                while (iterator.hasNext()) {
                    SqlTuningTask task = iterator.next().getValue();
                    if (!state.getConversations().containsKey(task.getConversationId())) {
                        iterator.remove();
                    }
                }
                return null;
            }
        });
    }

    private String makeTitle(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "新建调优";
        }
        String normalized = content.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() > 32 ? normalized.substring(0, 32) : normalized;
    }

    // 临界区返回的对象仍是共享引用的浅拷贝问题：Conversation/Message 是可变 POJO，
    // 复制一份再外泄，避免调用方拿到后修改影响共享状态或被并发覆盖。
    private static Conversation copy(Conversation source) {
        return new Conversation(source.getId(), source.getUserId(), source.getTitle(),
                source.getCreatedAt(), source.getUpdatedAt());
    }

    private static Message copy(Message source) {
        return new Message(source.getId(), source.getConversationId(), source.getRole(),
                source.getContent(), source.getTaskId(), source.getCreatedAt());
    }
}
