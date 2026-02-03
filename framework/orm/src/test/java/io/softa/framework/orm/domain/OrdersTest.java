package io.softa.framework.orm.domain;

import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.utils.JsonUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
class OrdersTest {

    @Test
    void of() {
        Orders orders = Orders.of("sequence desc, name, job asc, id desc");
        assert orders != null;
        assertEquals(4, orders.getOrderList().size());
        log.info("orders: {}", orders);
    }

    @Test
    void testSerialize() {
        Orders orders = Orders.of("sequence desc, name, job asc, id desc");
        String str = JsonUtils.objectToString(orders);
        Orders newOrders = JsonUtils.stringToObject(str, Orders.class);
        assert orders != null;
        assertEquals(orders.toString(), Objects.requireNonNull(Orders.of(newOrders.toString())).toString());
    }

    @Test
    void testListDeserialize() {
        Orders orders2 = JsonUtils.stringToObject("[[\"job\", \"asc\"], [\"id\", \"desc\"]]", Orders.class);
        Orders orders1 = Orders.of("job asc, id desc");
        assert orders1 != null;
        assertEquals(orders1.toString(), orders2.toString());
    }

    @Test
    void testDeserialize() {
        Orders orders = JsonUtils.stringToObject("[\"name\", \"ASC\"]", Orders.class);
        assertEquals(1, orders.getOrderList().size());
    }

    @Test
    void testDeserializeList() {
        Orders orders = JsonUtils.stringToObject("[[\"name\", \"ASC\"], [\"code\", \"desc\"]]", Orders.class);
        assertEquals(2, orders.getOrderList().size());
    }
}