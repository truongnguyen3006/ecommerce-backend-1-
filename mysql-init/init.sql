SET NAMES 'utf8mb4';
SET CHARACTER SET utf8mb4;
-- Tạo Database cho User Service
CREATE DATABASE IF NOT EXISTS `user-service`;
USE `user-service`;

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";

CREATE TABLE `t_user` (
                          `id` bigint(20) NOT NULL,
                          `address` varchar(255) DEFAULT NULL,
                          `created_date` datetime(6) DEFAULT NULL,
                          `email` varchar(255) DEFAULT NULL,
                          `full_name` varchar(255) DEFAULT NULL,
                          `keycloak_id` varchar(255) NOT NULL,
                          `phone_number` varchar(255) DEFAULT NULL,
                          `status` bit(1) NOT NULL,
                          `updated_date` datetime(6) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

ALTER TABLE `t_user`
    ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `UKrwp9w95chgkx3she6g4d4vqgi` (`keycloak_id`);

ALTER TABLE `t_user`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=6;
COMMIT;

-- Tạo Database cho Order Service
CREATE DATABASE IF NOT EXISTS `order-service`;
USE `order-service`;
SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";

CREATE TABLE `t_orders` (
                            `id` bigint(20) NOT NULL,
                            `order_date` datetime(6) DEFAULT NULL,
                            `order_number` varchar(255) NOT NULL,
                            `status` varchar(255) DEFAULT NULL,
                            `total_price` decimal(38,2) DEFAULT NULL,
                            `user_id` varchar(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE `t_orders_line_items` (
                                       `id` bigint(20) NOT NULL,
                                       `color` varchar(255) DEFAULT NULL,
                                       `price` decimal(38,2) DEFAULT NULL,
                                       `product_name` varchar(255) DEFAULT NULL,
                                       `quantity` int(11) DEFAULT NULL,
                                       `size` varchar(255) DEFAULT NULL,
                                       `sku_code` varchar(255) DEFAULT NULL,
                                       `order_id` bigint(20) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

ALTER TABLE `t_orders`
    ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `UKps1fn4nvjuwtd1legt1w0phlg` (`order_number`);

ALTER TABLE `t_orders_line_items`
    ADD PRIMARY KEY (`id`),
  ADD KEY `FKnk3963e62cm9ree6q0ihf99si` (`order_id`);

ALTER TABLE `t_orders`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

ALTER TABLE `t_orders_line_items`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT;

ALTER TABLE `t_orders_line_items`
    ADD CONSTRAINT `FKnk3963e62cm9ree6q0ihf99si` FOREIGN KEY (`order_id`) REFERENCES `t_orders` (`id`);
COMMIT;


-- Tạo Database cho Product Service
CREATE DATABASE IF NOT EXISTS `product-service`;
USE `product-service`;

SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";

CREATE TABLE `product` (
                           `id` bigint(20) NOT NULL,
                           `base_price` decimal(38,2) DEFAULT NULL,
                           `category` varchar(255) DEFAULT NULL,
                           `created_at` datetime(6) DEFAULT NULL,
                           `description` varchar(255) DEFAULT NULL,
                           `image_url` varchar(255) DEFAULT NULL,
                           `name` varchar(255) NOT NULL,
                           `updated_at` datetime(6) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO `product` (`id`, `base_price`, `category`, `created_at`, `description`, `image_url`, `name`, `updated_at`) VALUES
    (1, 3239000.00, 'Giày Nam', '2025-12-10 00:53:01.000000', 'The Field General returns from its gritty American football roots to shake up the sneaker scene.', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', 'Nike Field General Suede', '2025-12-10 00:53:01.000000');

CREATE TABLE `product_images` (
                                  `id` bigint(20) NOT NULL,
                                  `image_url` varchar(255) DEFAULT NULL,
                                  `variant_id` bigint(20) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;


INSERT INTO `product_images` (`id`, `image_url`, `variant_id`) VALUES
                                                                   (93, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', 1),
                                                                   (94, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/67ef0722-ead1-4a76-8d79-c17a6c176e70/NIKE+FIELD+GENERAL.png', 1),
                                                                   (95, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/ef4ea7ee-7a21-4694-a142-37c6de0dc729/NIKE+FIELD+GENERAL.png', 1),
                                                                   (96, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/4091b78d-3ced-43fc-b6e8-2a5689175e84/NIKE+FIELD+GENERAL.png', 1),
                                                                   (97, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', 2),
                                                                   (98, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/67ef0722-ead1-4a76-8d79-c17a6c176e70/NIKE+FIELD+GENERAL.png', 2),
                                                                   (99, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/ef4ea7ee-7a21-4694-a142-37c6de0dc729/NIKE+FIELD+GENERAL.png', 2),
                                                                   (100, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/4091b78d-3ced-43fc-b6e8-2a5689175e84/NIKE+FIELD+GENERAL.png', 2),
                                                                   (101, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', 3),
                                                                   (102, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/67ef0722-ead1-4a76-8d79-c17a6c176e70/NIKE+FIELD+GENERAL.png', 3),
                                                                   (103, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/ef4ea7ee-7a21-4694-a142-37c6de0dc729/NIKE+FIELD+GENERAL.png', 3),
                                                                   (104, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/4091b78d-3ced-43fc-b6e8-2a5689175e84/NIKE+FIELD+GENERAL.png', 3),
                                                                   (105, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', 4),
                                                                   (106, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/67ef0722-ead1-4a76-8d79-c17a6c176e70/NIKE+FIELD+GENERAL.png', 4),
                                                                   (107, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/ef4ea7ee-7a21-4694-a142-37c6de0dc729/NIKE+FIELD+GENERAL.png', 4),
                                                                   (108, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/4091b78d-3ced-43fc-b6e8-2a5689175e84/NIKE+FIELD+GENERAL.png', 4),
                                                                   (109, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', 5),
                                                                   (110, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/67ef0722-ead1-4a76-8d79-c17a6c176e70/NIKE+FIELD+GENERAL.png', 5),
                                                                   (111, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/ef4ea7ee-7a21-4694-a142-37c6de0dc729/NIKE+FIELD+GENERAL.png', 5),
                                                                   (112, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/4091b78d-3ced-43fc-b6e8-2a5689175e84/NIKE+FIELD+GENERAL.png', 5),
                                                                   (113, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', 6),
                                                                   (114, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/67ef0722-ead1-4a76-8d79-c17a6c176e70/NIKE+FIELD+GENERAL.png', 6),
                                                                   (115, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/ef4ea7ee-7a21-4694-a142-37c6de0dc729/NIKE+FIELD+GENERAL.png', 6),
                                                                   (116, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/4091b78d-3ced-43fc-b6e8-2a5689175e84/NIKE+FIELD+GENERAL.png', 6),
                                                                   (117, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', 7),
                                                                   (118, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/67ef0722-ead1-4a76-8d79-c17a6c176e70/NIKE+FIELD+GENERAL.png', 7),
                                                                   (119, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/ef4ea7ee-7a21-4694-a142-37c6de0dc729/NIKE+FIELD+GENERAL.png', 7),
                                                                   (120, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/4091b78d-3ced-43fc-b6e8-2a5689175e84/NIKE+FIELD+GENERAL.png', 7),
                                                                   (121, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', 8),
                                                                   (122, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/67ef0722-ead1-4a76-8d79-c17a6c176e70/NIKE+FIELD+GENERAL.png', 8),
                                                                   (123, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/ef4ea7ee-7a21-4694-a142-37c6de0dc729/NIKE+FIELD+GENERAL.png', 8),
                                                                   (124, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/4091b78d-3ced-43fc-b6e8-2a5689175e84/NIKE+FIELD+GENERAL.png', 8),
                                                                   (125, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', 9),
                                                                   (126, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/67ef0722-ead1-4a76-8d79-c17a6c176e70/NIKE+FIELD+GENERAL.png', 9),
                                                                   (127, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/ef4ea7ee-7a21-4694-a142-37c6de0dc729/NIKE+FIELD+GENERAL.png', 9),
                                                                   (128, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/4091b78d-3ced-43fc-b6e8-2a5689175e84/NIKE+FIELD+GENERAL.png', 9),
                                                                   (129, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/90809137-6681-46dd-9102-fe40f02edaa3/NIKE+FIELD+GENERAL.png', 10),
                                                                   (130, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c8f58b78-4df1-48c4-97bf-78f0ed146afb/NIKE+FIELD+GENERAL.png', 10),
                                                                   (131, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/3dad1aa4-845c-4e8a-800c-92a8f9f24408/NIKE+FIELD+GENERAL.png', 10),
                                                                   (132, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/14edf6b3-e12f-4fe9-a6b6-cfad95d18595/NIKE+FIELD+GENERAL.png', 10),
                                                                   (133, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/90809137-6681-46dd-9102-fe40f02edaa3/NIKE+FIELD+GENERAL.png', 11),
                                                                   (134, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c8f58b78-4df1-48c4-97bf-78f0ed146afb/NIKE+FIELD+GENERAL.png', 11),
                                                                   (135, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/3dad1aa4-845c-4e8a-800c-92a8f9f24408/NIKE+FIELD+GENERAL.png', 11),
                                                                   (136, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/14edf6b3-e12f-4fe9-a6b6-cfad95d18595/NIKE+FIELD+GENERAL.png', 11),
                                                                   (137, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/90809137-6681-46dd-9102-fe40f02edaa3/NIKE+FIELD+GENERAL.png', 12),
                                                                   (138, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c8f58b78-4df1-48c4-97bf-78f0ed146afb/NIKE+FIELD+GENERAL.png', 12),
                                                                   (139, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/3dad1aa4-845c-4e8a-800c-92a8f9f24408/NIKE+FIELD+GENERAL.png', 12),
                                                                   (140, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/14edf6b3-e12f-4fe9-a6b6-cfad95d18595/NIKE+FIELD+GENERAL.png', 12),
                                                                   (141, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/90809137-6681-46dd-9102-fe40f02edaa3/NIKE+FIELD+GENERAL.png', 13),
                                                                   (142, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c8f58b78-4df1-48c4-97bf-78f0ed146afb/NIKE+FIELD+GENERAL.png', 13),
                                                                   (143, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/3dad1aa4-845c-4e8a-800c-92a8f9f24408/NIKE+FIELD+GENERAL.png', 13),
                                                                   (144, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/14edf6b3-e12f-4fe9-a6b6-cfad95d18595/NIKE+FIELD+GENERAL.png', 13),
                                                                   (145, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/90809137-6681-46dd-9102-fe40f02edaa3/NIKE+FIELD+GENERAL.png', 14),
                                                                   (146, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c8f58b78-4df1-48c4-97bf-78f0ed146afb/NIKE+FIELD+GENERAL.png', 14),
                                                                   (147, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/3dad1aa4-845c-4e8a-800c-92a8f9f24408/NIKE+FIELD+GENERAL.png', 14),
                                                                   (148, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/14edf6b3-e12f-4fe9-a6b6-cfad95d18595/NIKE+FIELD+GENERAL.png', 14),
                                                                   (149, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', 15),
                                                                   (150, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/e814d014-68f4-4bd6-8d32-cc7eec12cd8a/NIKE+FIELD+GENERAL.png', 15),
                                                                   (151, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/1203d72a-513d-4ac7-808d-f0133321287a/NIKE+FIELD+GENERAL.png', 15),
                                                                   (152, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/3b67a6c1-36cb-4133-9760-a85b67f7b46f/NIKE+FIELD+GENERAL.png', 15),
                                                                   (153, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', 16),
                                                                   (154, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/e814d014-68f4-4bd6-8d32-cc7eec12cd8a/NIKE+FIELD+GENERAL.png', 16),
                                                                   (155, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/1203d72a-513d-4ac7-808d-f0133321287a/NIKE+FIELD+GENERAL.png', 16),
                                                                   (156, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/3b67a6c1-36cb-4133-9760-a85b67f7b46f/NIKE+FIELD+GENERAL.png', 16),
                                                                   (157, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', 17),
                                                                   (158, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/e814d014-68f4-4bd6-8d32-cc7eec12cd8a/NIKE+FIELD+GENERAL.png', 17),
                                                                   (159, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/1203d72a-513d-4ac7-808d-f0133321287a/NIKE+FIELD+GENERAL.png', 17),
                                                                   (160, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/3b67a6c1-36cb-4133-9760-a85b67f7b46f/NIKE+FIELD+GENERAL.png', 17),
                                                                   (161, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', 18),
                                                                   (162, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/e814d014-68f4-4bd6-8d32-cc7eec12cd8a/NIKE+FIELD+GENERAL.png', 18),
                                                                   (163, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/1203d72a-513d-4ac7-808d-f0133321287a/NIKE+FIELD+GENERAL.png', 18),
                                                                   (164, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/3b67a6c1-36cb-4133-9760-a85b67f7b46f/NIKE+FIELD+GENERAL.png', 18),
                                                                   (165, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', 19),
                                                                   (166, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/e814d014-68f4-4bd6-8d32-cc7eec12cd8a/NIKE+FIELD+GENERAL.png', 19),
                                                                   (167, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/1203d72a-513d-4ac7-808d-f0133321287a/NIKE+FIELD+GENERAL.png', 19),
                                                                   (168, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/3b67a6c1-36cb-4133-9760-a85b67f7b46f/NIKE+FIELD+GENERAL.png', 19),
                                                                   (169, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', 20),
                                                                   (170, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/e814d014-68f4-4bd6-8d32-cc7eec12cd8a/NIKE+FIELD+GENERAL.png', 20),
                                                                   (171, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/1203d72a-513d-4ac7-808d-f0133321287a/NIKE+FIELD+GENERAL.png', 20),
                                                                   (172, 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/3b67a6c1-36cb-4133-9760-a85b67f7b46f/NIKE+FIELD+GENERAL.png', 20);

CREATE TABLE `product_variant` (
                                   `id` bigint(20) NOT NULL,
                                   `color` varchar(255) DEFAULT NULL,
                                   `image_url` varchar(255) DEFAULT NULL,
                                   `is_active` bit(1) DEFAULT NULL,
                                   `price` decimal(38,2) DEFAULT NULL,
                                   `size` varchar(255) DEFAULT NULL,
                                   `sku_code` varchar(255) NOT NULL,
                                   `product_id` bigint(20) DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO `product_variant` (`id`, `color`, `image_url`, `is_active`, `price`, `size`, `sku_code`, `product_id`) VALUES
                                                                                                                       (1, 'White', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '38', 'NIK1-WHITE-38', 1),
                                                                                                                       (2, 'White', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '39', 'NIK1-WHITE-39', 1),
                                                                                                                       (3, 'White', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '40', 'NIK1-WHITE-40', 1),
                                                                                                                       (4, 'White', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '41', 'NIK1-WHITE-41', 1),
                                                                                                                       (5, 'White', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '42', 'NIK1-WHITE-42', 1),
                                                                                                                       (6, 'White', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '43', 'NIK1-WHITE-43', 1),
                                                                                                                       (7, 'White', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '44', 'NIK1-WHITE-44', 1),
                                                                                                                       (8, 'White', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '45', 'NIK1-WHITE-45', 1),
                                                                                                                       (9, 'White', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/f6acfa1a-ad5d-487a-9e62-e4be89059802/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '46', 'NIK1-WHITE-46', 1),
                                                                                                                       (10, 'Blue', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/90809137-6681-46dd-9102-fe40f02edaa3/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '40', 'NIK1-BLUE-40', 1),
                                                                                                                       (11, 'Blue', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/90809137-6681-46dd-9102-fe40f02edaa3/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '42', 'NIK1-BLUE-42', 1),
                                                                                                                       (12, 'Blue', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/90809137-6681-46dd-9102-fe40f02edaa3/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '41', 'NIK1-BLUE-41', 1),
                                                                                                                       (13, 'Blue', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/90809137-6681-46dd-9102-fe40f02edaa3/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '39', 'NIK1-BLUE-39', 1),
                                                                                                                       (14, 'Blue', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/90809137-6681-46dd-9102-fe40f02edaa3/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '38', 'NIK1-BLUE-38', 1),
                                                                                                                       (15, 'Green', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '38', 'NIK1-GREEN-38', 1),
                                                                                                                       (16, 'Green', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '39', 'NIK1-GREEN-39', 1),
                                                                                                                       (17, 'Green', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '41', 'NIK1-GREEN-41', 1),
                                                                                                                       (18, 'Green', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '40', 'NIK1-GREEN-40', 1),
                                                                                                                       (19, 'Green', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '43', 'NIK1-GREEN-43', 1),
                                                                                                                       (20, 'Green', 'https://static.nike.com/a/images/t_web_pdp_535_v2/f_auto/c302faba-0786-4d7f-9de5-748d9d73838e/NIKE+FIELD+GENERAL.png', b'1', 3239000.00, '42', 'NIK1-GREEN-42', 1);

ALTER TABLE `product`
    ADD PRIMARY KEY (`id`);

ALTER TABLE `product_images`
    ADD PRIMARY KEY (`id`),
  ADD KEY `FKd8g8mufkd2s56xdbljm63e4y3` (`variant_id`);

ALTER TABLE `product_variant`
    ADD PRIMARY KEY (`id`),
  ADD UNIQUE KEY `UK3dx175f6ym9hrjsi8fl2ej192` (`sku_code`),
  ADD KEY `FKgrbbs9t374m9gg43l6tq1xwdj` (`product_id`);

ALTER TABLE `product`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=2;

ALTER TABLE `product_images`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=173;

ALTER TABLE `product_variant`
    MODIFY `id` bigint(20) NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=21;

ALTER TABLE `product_images`
    ADD CONSTRAINT `FKd8g8mufkd2s56xdbljm63e4y3` FOREIGN KEY (`variant_id`) REFERENCES `product_variant` (`id`);

ALTER TABLE `product_variant`
    ADD CONSTRAINT `FKgrbbs9t374m9gg43l6tq1xwdj` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`);
COMMIT;

ALTER TABLE `product` AUTO_INCREMENT = 100;         -- Giả sử bạn đã insert đến ID 20
ALTER TABLE `product_variant` AUTO_INCREMENT = 100;
ALTER TABLE `product_images` AUTO_INCREMENT = 200;

COMMIT;