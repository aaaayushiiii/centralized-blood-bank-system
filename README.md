# Centralized Blood Bank & Emergency Routing

BACSE202 Database Systems course project.

## Purpose

Hospital blood banks often manage inventory independently, which makes compatible blood units difficult to locate during emergencies. This project proposes a common database for donor records, hospital inventory, emergency requests and blood-unit allocation, with expiry monitoring and a history of blood-bag status changes.

The proposed system supports hospital and blood bank staff, emergency and medical staff, administrators, and donors. Requests are prioritized as Critical, Urgent or Routine. The intended scope includes matching compatible available units, tracking allocations, and identifying low-stock or expired/near-expiry inventory.

## Current status

Phase 1 is completed. The supplied Review 1 document is preserved in [docs/phase-1/review-1.pdf](docs/phase-1/review-1.pdf) and remains the reference for the completed schema. This repository setup prepares folders for Phase 2 only; it contains no application implementation or generated database scripts.

## Six tables

The following descriptions summarize the existing Phase 1 design without changing its schema, attributes, keys or constraints.

| Table | Purpose |
| --- | --- |
| DONOR | Stores donor details, blood group, Rh factor and donation information. |
| HOSPITAL | Stores hospital details, location and contact information; hospitals hold inventory and place requests. |
| BLOOD_BAGS | Tracks individual blood units, their donor and stocking hospital, blood group, Rh factor, collection and expiry dates, and current status. |
| REQUEST | Records hospital blood requirements, requested units, blood group, Rh factor, priority, request time and status. |
| ALLOCATION | Links requests to assigned blood bags and records allocation time. The Phase 1 design makes BAG_ID unique to prevent double allocation. |
| BAG_STATUS_LOG | Records blood-bag status changes, including old and new status, change time and reason. |

Refer to Review 1 for the original ER/relational model and normalization through 1NF, 2NF and 3NF. No separate diagram or schema document has been created for this setup.

## Development phases

The milestones below follow the supplied [course overview](docs/course-overview.pptx). Completion percentages are course targets, not claims about implemented code in this repository.

| Phase | Course milestone | Scope | Repository status |
| --- | --- | --- | --- |
| Phase 1 - Design foundation | Assessment 8; 30%; 10 marks | Problem identification, requirement analysis, ER/EER modelling, relational mapping and normalization. | Completed; supplied Review 1 PDF archived unchanged. |
| Phase 2 - Implementation core | Assessment 9; 80%; 10 marks | Future table creation and data population, front-end design, business logic and initial ODBC/JDBC connectivity; Phase 2 presentation. | Folder structure prepared only; implementation has not been added. |
| Phase 3 - Integration and delivery | Assessment 10; 100%; 10 marks | Complete ODBC/JDBC CRUD integration, testing and validation, demonstration, final presentation and project report. | Future work. |

## Repository layout

```text
centralized-blood-bank-system/
|-- README.md
|-- .gitignore
|-- docs/
|   |-- course-overview.pptx
|   |-- phase-1/
|   |   `-- review-1.pdf
|   `-- phase-2/
|       `-- README.md
|-- database/
|   `-- README.md
|-- frontend/
|   `-- README.md
`-- backend/
    `-- README.md
```

Both supplied documents are copied into the repository without altering their contents. Their original files remain in the parent workspace folder. Future implementation must remain consistent with the completed Phase 1 schema.
