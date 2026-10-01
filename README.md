# Payment Reconciliation Simulator

A personal Java project for learning AWS and MongoDB through simulated daily payment reconciliation. A Spring Boot API on EC2 owns transaction storage and exposes results through Swagger. A Java Lambda compares an S3 settlement CSV with closed-day purchase records obtained from that API.

## Project status

The application is currently the Spring Initializr starter with empty purchase controller, model, repository and service classes. The documentation proposes the agreed one-week MVP; endpoints, reconciliation processing, Swagger and AWS infrastructure have not yet been implemented.

## Specification and development

- [MVP specification and architecture diagram](docs/superpowers/specs/2026-10-01-payment-reconciliation-design.md)
- [Spec driven development workflow](docs/spec-driven-development.md)

Review the written specification first. The next artifact is an implementation plan whose tasks link requirements to acceptance criteria. This project processes generated data only and does not process cards or move money.

## Starter baseline

The starter uses Java 21, Spring Boot 4.1.1, Maven, Spring MVC, Spring Data MongoDB and Testcontainers. The checked-in wrapper supports Maven execution on Windows and Unix. Dependency compatibility and a working build will be verified during implementation; this documentation change does not claim the starter has been built or tested.
