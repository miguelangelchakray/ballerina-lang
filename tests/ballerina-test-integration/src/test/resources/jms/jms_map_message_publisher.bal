import ballerina/jms;
import ballerina/io;

// Initialize a JMS connection with the provider
jms:Connection jmsConnection = new({
        initialContextFactory: "bmbInitialContextFactory",
        providerUrl: "amqp://admin:admin@carbon/carbon?brokerlist='tcp://localhost:5772'"
    });

// Initialize a JMS session on top of the created connection
jms:Session jmsSession = new(jmsConnection, {
        acknowledgementMode: "AUTO_ACKNOWLEDGE"
    });

endpoint jms:TopicPublisher publisher {
    session: jmsSession,
    topicPattern: "testMapMessageSubscriber"
};

function main(string... args) {
    // Create a Text message.
    string stringValue = "abcde";
    blob blobValue = stringValue.toBlob("UTF-8");
    map message = { "a": 1, "b": "abc", "c": true, "d": 1.2, "e": blobValue };
    jms:Message m = check jmsSession.createMapMessage(message);
    // Send the Ballerina message to the JMS provider.
    _ = publisher->send(m);

    io:println("Message successfully sent by TopicPublisher");
}
