### http-async-proccessing-example

Proof of concept of using two endpoints to handle requests, that take a long time to process, asynchronously.

This example provides endpoints to sum two numbers:

- `/sum/{a}/{b}` : The normal endpoint that returns a response when the request has been processed.
- `/async/sum/{a}/{b}` L An endpoint that accepts the request and returns the location where the result will be made available.
- `/async/result/sum/{correlationId}`: An endpoint that will respond with the response once the sum has been computed.

The calculation of the sum has an artificial delay of 5 seconds.
Play with the client connection configuration to see how it influences requests using the first endpoint compared to requests using the last two endpoints.

#### Running the example

- Start the server with `sbt 'runMain example.ServerApp'`
- Start the client with `sbt 'runMain example.ClientApp'`
